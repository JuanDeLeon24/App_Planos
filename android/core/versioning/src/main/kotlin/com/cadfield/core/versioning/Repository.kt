package com.cadfield.core.versioning

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Repositorio de versiones simplificado para usuarios técnicos ("Git para planos").
 *
 *  - Cada guardado explícito = commit con versión (V1.0, V1.1...), fecha, usuario y comentario.
 *  - Ramas (p.ej. "campo-frente-norte") y fusión de observaciones.
 *  - Restaurar = crear un NUEVO commit con el contenido antiguo (el historial nunca se reescribe).
 *  - Los originales nunca se sobrescriben: viven en el ObjectStore inmutable.
 *
 * Diseño de almacenamiento:
 *   objects/  → blobs (archivos CAD, fotos, audio) + snapshots + commits (JSON), por SHA-256
 *   refs/     → una línea por rama con el id del commit cabeza (escritura atómica)
 */
class VersionRepository(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {

    val objects = FileObjectStore(File(dir, "objects"))
    private val refs = File(dir, "refs").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun branches(): List<String> = refs.listFiles()?.map { it.name }?.sorted().orEmpty()

    fun head(branch: String = MAIN): Commit? =
        File(refs, branch).takeIf { it.exists() }?.readText()?.trim()?.let(::commit)

    fun commit(id: String): Commit = json.decodeFromString<Commit>(objects.get(id).decodeToString()).copy(id = id)

    fun snapshot(commit: Commit): Snapshot = json.decodeFromString(objects.get(commit.snapshotHash).decodeToString())

    /** Importa un plano original como V1.0 (nunca se modificará). */
    fun init(originalFile: File, author: String, message: String = "Plano Original"): Commit {
        check(head(MAIN) == null) { "Repositorio ya inicializado" }
        val baseHash = originalFile.inputStream().use { objects.put(it) }
        return commitSnapshot(Snapshot(baseHash, emptyMap()), author, message, MAIN, parents = emptyList(), label = "V1.0")
    }

    fun commit(snapshot: Snapshot, author: String, message: String, branch: String = MAIN, signer: ((String) -> String)? = null): Commit {
        val parent = head(branch) ?: error("Rama inexistente: $branch")
        if (snapshot == snapshot(parent)) return parent // nada que versionar
        return commitSnapshot(snapshot, author, message, branch, listOf(parent.id), nextLabel(parent.label, branch), signer)
    }

    fun createBranch(name: String, from: String = MAIN) {
        require(name.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Nombre de rama inválido" }
        require(!File(refs, name).exists()) { "La rama ya existe" }
        writeRef(name, head(from)?.id ?: error("Rama origen vacía"))
    }

    fun log(branch: String = MAIN, limit: Int = Int.MAX_VALUE): List<Commit> {
        val out = ArrayList<Commit>()
        var c = head(branch)
        while (c != null && out.size < limit) {
            out += c
            c = c.parents.firstOrNull()?.let(::commit)
        }
        return out
    }

    fun diff(fromId: String, toId: String) = ThreeWayMerge.diff(snapshot(commit(fromId)), snapshot(commit(toId)))

    /** Restaura una versión anterior creando un nuevo commit (no destructivo). */
    fun restore(commitId: String, author: String, branch: String = MAIN): Commit {
        val target = commit(commitId)
        return commit(snapshot(target), author, "Restaurado desde ${target.label}", branch)
    }

    /** Fusiona [source] en [target]. Devuelve el commit de fusión y los conflictos (si los hay). */
    fun merge(source: String, target: String, author: String): Pair<Commit, List<MergeConflict>> {
        val s = head(source) ?: error("Rama inexistente: $source")
        val t = head(target) ?: error("Rama inexistente: $target")
        val base = mergeBase(s.id, t.id)?.let(::commit)
        if (base?.id == s.id) return t to emptyList() // nada nuevo
        if (base?.id == t.id) { writeRef(target, s.id); return s to emptyList() } // fast-forward
        val result = ThreeWayMerge.merge(
            base?.let(::snapshot) ?: Snapshot(snapshot(t).baseFileHash, emptyMap()), snapshot(t), snapshot(s),
        )
        val major = t.label.removePrefix("V").substringBefore('.').toIntOrNull() ?: 1
        val c = commitSnapshot(result.snapshot, author, "Fusión de '$source' en '$target'" +
            if (result.conflicts.isNotEmpty()) " (${result.conflicts.size} conflictos conservados)" else "",
            target, listOf(t.id, s.id), "V${major + 1}.0")
        return c to result.conflicts
    }

    fun mergeBase(a: String, b: String): String? {
        val ancestorsA = HashSet<String>()
        val queue = ArrayDeque(listOf(a))
        while (queue.isNotEmpty()) { val c = queue.removeFirst(); if (ancestorsA.add(c)) queue += commit(c).parents }
        val q2 = ArrayDeque(listOf(b)); val seen = HashSet<String>()
        while (q2.isNotEmpty()) {
            val c = q2.removeFirst()
            if (c in ancestorsA) return c
            if (seen.add(c)) q2 += commit(c).parents
        }
        return null
    }

    /** Verificación de integridad completa (fsck). Devuelve hashes corruptos/faltantes. */
    fun fsck(): List<String> {
        val bad = ArrayList<String>()
        for (b in branches()) for (c in log(b)) {
            if (!objects.verify(c.id)) bad += c.id
            if (!objects.verify(c.snapshotHash)) bad += c.snapshotHash
            else if (!objects.verify(snapshot(c).baseFileHash)) bad += snapshot(c).baseFileHash
        }
        return bad.distinct()
    }

    // ---------- internos ----------

    private fun commitSnapshot(
        snap: Snapshot, author: String, message: String, branch: String,
        parents: List<String>, label: String, signer: ((String) -> String)? = null,
    ): Commit {
        val snapHash = objects.put(json.encodeToString(snap).encodeToByteArray())
        val ts = clock()
        val draft = Commit("", parents, snapHash, author, ts, message, label, branch)
        val signed = draft.copy(signature = signer?.invoke(draft.signingPayload()))
        val bytes = json.encodeToString(signed).encodeToByteArray()
        val id = objects.put(bytes)
        // el id es el hash del commit serializado sin id → se reescribe el campo id sólo en memoria
        writeRef(branch, id)
        return signed.copy(id = id)
    }

    private fun nextLabel(prev: String, branch: String): String {
        val (maj, min) = prev.removePrefix("V").split('.').let { (it.getOrNull(0)?.toIntOrNull() ?: 1) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
        return "V$maj.${min + 1}"
    }

    private fun writeRef(branch: String, id: String) {
        val tmp = File(refs, ".$branch.tmp")
        tmp.outputStream().use { it.write(id.toByteArray()); it.fd.sync() }
        Files.move(tmp.toPath(), File(refs, branch).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object { const val MAIN = "principal" }
}
