package com.cadfield.viewer.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val client: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val remoteId: String? = null,
)

/**
 * Documento técnico. El archivo original es inmutable y vive en el repositorio de
 * versiones (objeto SHA-256 = [originalHash]); este registro es sólo el índice.
 */
@Entity(
    tableName = "documents",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("projectId"), Index("originalHash"), Index("discipline"), Index("front")],
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val displayName: String,
    val originalFileName: String,
    val format: String,
    val sizeBytes: Long,
    val originalHash: String,
    // Clasificación técnica
    val front: String? = null,       // Frente de obra
    val tunnel: String? = null,      // Túnel / galería
    val discipline: String? = null,  // Civil, Geología, Topografía, Eléctrica...
    val documentDate: Long? = null,
    val currentVersionLabel: String = "V1.0",
    val headCommitId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val syncState: SyncState = SyncState.LOCAL_ONLY,
)

enum class SyncState { LOCAL_ONLY, PENDING_UPLOAD, SYNCED, CONFLICT }

@Entity(tableName = "tags")
data class TagEntity(@PrimaryKey val name: String, val color: Int = 0xFF4FC3F7.toInt())

@Entity(tableName = "document_tags", primaryKeys = ["documentId", "tag"], indices = [Index("tag")])
data class DocumentTagEntity(val documentId: String, val tag: String)

/** Índice local de versiones (el contenido está en el repo de objetos). */
@Entity(tableName = "versions", indices = [Index("documentId")])
data class VersionEntity(
    @PrimaryKey val commitId: String,
    val documentId: String,
    val label: String,
    val branch: String,
    val author: String,
    val message: String,
    val timestamp: Long,
    val parentIds: String,
    val signed: Boolean,
    val pushed: Boolean = false,
)

/** Cola de sincronización persistente (outbox): sobrevive a reinicios y falta de red. */
@Entity(tableName = "sync_queue", indices = [Index("documentId")])
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: String,
    val kind: String,        // OBJECT | COMMIT | REF | METADATA
    val ref: String,         // hash / commitId / rama
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

/** Auditoría completa (quién, qué, cuándo, dónde). Sólo inserción. */
@Entity(tableName = "audit_log", indices = [Index("documentId"), Index("timestamp")])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val user: String,
    val action: String,
    val documentId: String?,
    val detail: String,
    val lat: Double? = null,
    val lon: Double? = null,
)
