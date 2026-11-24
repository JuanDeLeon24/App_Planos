package com.cadfield.viewer.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cadfield.core.versioning.VersionRepository
import com.cadfield.viewer.BuildConfig
import com.cadfield.viewer.container
import com.cadfield.viewer.data.db.SyncState
import java.util.concurrent.TimeUnit

/**
 * Sincronización en segundo plano (offline-first):
 *  1. Toma elementos del outbox (sync_queue) en orden.
 *  2. Negocia qué objetos faltan en el servidor y sube SÓLO esos (diferencial, deduplicado).
 *  3. Avanza la rama remota con compare-and-swap.
 *  4. Si la rama remota avanzó (otro usuario), descarga sus objetos, fusiona localmente
 *     (fusión de 3 vías que conserva ambas versiones en conflicto) y reintenta.
 * Reintentos con backoff exponencial; nada se elimina del outbox hasta confirmarse.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val token = TokenStore(applicationContext).token ?: return Result.success() // modo 100% offline
        val client = SyncClient(BuildConfig.API_BASE_URL, { token })
        val queue = c.db.syncQueue()
        for (item in queue.peek(50)) {
            try {
                if (item.kind == "COMMIT") pushCommit(c.documents.repo(item.documentId), item.documentId, item.ref, client, c.currentUser)
                queue.done(item.id)
                c.db.documents().get(item.documentId)?.let { c.db.documents().update(it.copy(syncState = SyncState.SYNCED)) }
            } catch (e: AuthException) {
                return Result.failure()
            } catch (e: Exception) {
                queue.update(item.copy(attempts = item.attempts + 1, lastError = e.message?.take(500)))
                return Result.retry()
            }
        }
        return Result.success()
    }

    private suspend fun pushCommit(repo: VersionRepository, docId: String, commitId: String, client: SyncClient, user: String) {
        val branch = repo.commit(commitId).branch
        val local = repo.head(branch)?.id ?: return
        // cierre transitivo de objetos de la rama (acotado a lo que el servidor no tiene)
        val needed = repo.log(branch).flatMap { repo.objectsOf(it.id) }.distinct()
        for (h in client.negotiate(docId, needed)) {
            repo.objects.open(h).use { client.uploadObject(h, it, repo.objects.size(h)) }
        }
        val remote = client.refs(docId).firstOrNull { it.branch == branch }?.commitId
        try {
            client.updateRef(docId, RefUpdate(branch, remote, local))
        } catch (e: ConflictException) {
            fetchChain(repo, client, e.remoteHead)
            val remoteBranch = "remoto.$branch"
            repo.updateRef(remoteBranch, e.remoteHead)
            val (merged, conflicts) = repo.merge(remoteBranch, branch, user)
            if (conflicts.isNotEmpty()) {
                applicationContext.container.db.documents().get(docId)?.let {
                    applicationContext.container.db.documents().update(it.copy(syncState = SyncState.CONFLICT))
                }
            }
            pushCommit(repo, docId, merged.id, client, user)
        }
    }

    /** Descarga commits remotos (y sus snapshots/planos) que no existan localmente. */
    private fun fetchChain(repo: VersionRepository, client: SyncClient, head: String) {
        val stack = ArrayDeque(listOf(head))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (repo.objects.exists(id)) continue
            check(repo.objects.put(client.downloadObject(id)) == id) { "Hash remoto no coincide" }
            val commit = repo.commit(id)
            if (!repo.objects.exists(commit.snapshotHash)) repo.objects.put(client.downloadObject(commit.snapshotHash))
            val base = repo.snapshot(commit).baseFileHash
            if (!repo.objects.exists(base)) repo.objects.put(client.downloadObject(base))
            stack += commit.parents
        }
    }
}

object SyncScheduler {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED) // configurable: planos pesados por Wi-Fi
        .setRequiresBatteryNotLow(true)
        .build()

    fun schedulePeriodic(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
        "sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build(),
    )

    fun syncNow(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork(
        "sync-now", ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build(),
    )
}

/** Almacén del token JWT (preferencias privadas; el token es de corta duración + refresh). */
class TokenStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
    var token: String?
        get() = prefs.getString("jwt", null)
        set(v) = prefs.edit().putString("jwt", v).apply()
}
