package com.cadfield.core.versioning

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Operación de edición atómica registrada en el journal. */
@Serializable
sealed class EditOp {
    @Serializable data class Upsert(val record: AnnotationRecord) : EditOp()
    @Serializable data class Delete(val id: String, val author: String, val at: Long) : EditOp()
    @Serializable data class SetMeta(val key: String, val value: String) : EditOp()
}

/**
 * Sesión de edición de un documento con protección total contra pérdida:
 *
 *  1. Cada operación → journal (fsync) ANTES de aplicarse (write-ahead).
 *  2. Autosave (cada 30 s si hay cambios) → working.json atómico + checkpoint del journal.
 *  3. Al reabrir tras un cierre inesperado: working.json + replay del journal = estado exacto.
 *  4. "Guardar versión" → commit inmutable en el repositorio (ver DocumentRepository).
 */
class EditSession(private val dir: File, base: Snapshot) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "op" }
    private val workingFile = File(dir, "working.json")
    private val journal: Journal
    private var appliedSeq = 0L

    var snapshot: Snapshot = base
        private set
    var dirty = false
        private set
    /** Número de operaciones recuperadas del journal al abrir (para avisar al usuario). */
    val recoveredOps: Int

    init {
        dir.mkdirs()
        if (workingFile.exists()) {
            runCatching { json.decodeFromString<WorkingState>(workingFile.readText()) }.getOrNull()?.let {
                snapshot = it.snapshot; appliedSeq = it.seq
            }
        }
        journal = Journal(File(dir, "edits.wal"))
        val pending = journal.replay(appliedSeq)
        pending.forEach { e -> apply(json.decodeFromString<EditOp>(e.payload)); appliedSeq = e.seq }
        recoveredOps = pending.size
        dirty = pending.isNotEmpty()
    }

    @Synchronized
    fun execute(op: EditOp) {
        appliedSeq = journal.append(json.encodeToString(op))
        apply(op)
        dirty = true
    }

    private fun apply(op: EditOp) {
        snapshot = when (op) {
            is EditOp.Upsert -> snapshot.copy(annotations = snapshot.annotations + (op.record.id to op.record))
            is EditOp.Delete -> snapshot.annotations[op.id]?.let {
                // borrado lógico: la anotación queda en el historial
                snapshot.copy(annotations = snapshot.annotations + (op.id to it.copy(deleted = true, modifiedAt = op.at, author = op.author)))
            } ?: snapshot
            is EditOp.SetMeta -> snapshot.copy(metadata = snapshot.metadata + (op.key to op.value))
        }
    }

    /** Autosave: persiste el estado de trabajo y compacta el journal. */
    @Synchronized
    fun autosave(): Boolean {
        if (!dirty) return false
        val tmp = File(dir, "working.json.tmp")
        tmp.outputStream().use { it.write(json.encodeToString(WorkingState(snapshot, appliedSeq)).toByteArray()); it.fd.sync() }
        Files.move(tmp.toPath(), workingFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        journal.checkpoint(appliedSeq)
        dirty = false
        return true
    }

    /** Tras crear un commit, el estado de trabajo coincide con la versión guardada. */
    fun markCommitted() { autosave() }

    /** Descarta el estado de trabajo y vuelve a [base] (p.ej. tras restaurar una versión). */
    @Synchronized
    fun reset(base: Snapshot) {
        snapshot = base
        dirty = true
        autosave()
    }

    override fun close() { autosave(); journal.close() }

    @Serializable private data class WorkingState(val snapshot: Snapshot, val seq: Long)
}
