package com.cadfield.core.versioning

import kotlinx.serialization.Serializable

/**
 * Registro de anotación/observación de campo. El plano original es inmutable;
 * TODO lo que el usuario agrega vive como capa de anotaciones versionada.
 */
@Serializable
data class AnnotationRecord(
    val id: String,
    val type: String,          // TEXT, ARROW, CLOUD, SYMBOL, PHOTO, VIDEO, AUDIO, MEASURE...
    val payload: String,       // JSON específico del tipo (geometría, texto, refs a blobs)
    val author: String,
    val modifiedAt: Long,
    val deleted: Boolean = false,
)

/** Estado completo de un documento en una versión: plano base + anotaciones + metadatos. */
@Serializable
data class Snapshot(
    val baseFileHash: String,
    val annotations: Map<String, AnnotationRecord>,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class Commit(
    val id: String,
    val parents: List<String>,
    val snapshotHash: String,
    val author: String,
    val timestamp: Long,
    val message: String,
    /** Etiqueta legible tipo "V1.2". */
    val label: String,
    val branch: String,
    /** Firma digital opcional (Base64) sobre [signingPayload]. */
    val signature: String? = null,
) {
    fun signingPayload() = "$parents|$snapshotHash|$author|$timestamp|$message|$label|$branch"
}

/** Diferencias entre dos snapshots (para "comparar versiones"). */
data class SnapshotDiff(
    val added: List<AnnotationRecord>,
    val removed: List<AnnotationRecord>,
    val modified: List<Pair<AnnotationRecord, AnnotationRecord>>,
    val baseChanged: Boolean,
) {
    val isEmpty get() = added.isEmpty() && removed.isEmpty() && modified.isEmpty() && !baseChanged
}

data class MergeConflict(val id: String, val ours: AnnotationRecord?, val theirs: AnnotationRecord?)

data class MergeResult(val snapshot: Snapshot, val conflicts: List<MergeConflict>)
