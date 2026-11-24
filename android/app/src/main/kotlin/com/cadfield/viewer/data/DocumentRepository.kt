package com.cadfield.viewer.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.cadfield.core.cad.dxf.CadFormat
import com.cadfield.core.cad.dxf.CadImporter
import com.cadfield.core.cad.dxf.DxfParser
import com.cadfield.core.cad.dxf.FormatDetector
import com.cadfield.core.cad.dxf.UnsupportedFormatException
import com.cadfield.core.cad.model.CadDocument
import com.cadfield.core.versioning.Commit
import com.cadfield.core.versioning.EditSession
import com.cadfield.core.versioning.SnapshotDiff
import com.cadfield.core.versioning.VersionRepository
import com.cadfield.viewer.data.db.AppDatabase
import com.cadfield.viewer.data.db.AuditEntity
import com.cadfield.viewer.data.db.DocumentEntity
import com.cadfield.viewer.data.db.DocumentTagEntity
import com.cadfield.viewer.data.db.ProjectEntity
import com.cadfield.viewer.data.db.SyncQueueEntity
import com.cadfield.viewer.data.db.SyncState
import com.cadfield.viewer.data.db.TagEntity
import com.cadfield.viewer.data.db.VersionEntity
import com.cadfield.viewer.security.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

data class ImportMetadata(
    val projectId: String,
    val front: String? = null,
    val tunnel: String? = null,
    val discipline: String? = null,
)

/**
 * Fachada de datos: índice Room + repositorio de versiones por documento.
 * Estructura en disco:  files/docs/<docId>/repo/{objects,refs}  y  files/docs/<docId>/session/
 */
class DocumentRepository(private val context: Context, private val db: AppDatabase) {

    private val docsRoot = File(context.filesDir, "docs")
    private val repos = HashMap<String, VersionRepository>()
    /** Motores externos registrados (ODA para DWG/DGN, IFC...). Ver docs/03-arquitectura.md. */
    val importers = mutableListOf<CadImporter>()

    fun observeProjects() = db.projects().observeAll()
    fun observeDocuments(projectId: String?, discipline: String?, front: String?, query: String) =
        db.documents().observe(projectId, discipline, front, query)
    fun observeDisciplines() = db.documents().observeDisciplines()
    fun observeVersions(docId: String) = db.versions().observe(docId)
    fun observePendingSync() = db.syncQueue().observeCount()

    suspend fun createProject(name: String, user: String): ProjectEntity {
        val now = System.currentTimeMillis()
        val p = ProjectEntity(UUID.randomUUID().toString(), name.trim(), createdAt = now, updatedAt = now)
        db.projects().insert(p)
        audit(user, "PROJECT_CREATE", null, name)
        return p
    }

    fun repo(docId: String): VersionRepository =
        synchronized(repos) { repos.getOrPut(docId) { VersionRepository(File(File(docsRoot, docId), "repo")) } }

    /** Importa un archivo: lo copia como original inmutable (V1.0). Nunca toca el archivo de origen. */
    suspend fun import(uri: Uri, meta: ImportMetadata, user: String): DocumentEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "plano"
        val tmpDir = File(docsRoot, "tmp").apply { mkdirs() }
        val tmp = File.createTempFile("import", ".bin", tmpDir)
        try {
            resolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            val head = tmp.inputStream().use { s -> ByteArray(64).also { s.read(it) } }
            val format = FormatDetector.detect(head, name)
            val id = UUID.randomUUID().toString()
            val v1 = repo(id).init(tmp, user)
            val now = System.currentTimeMillis()
            val doc = DocumentEntity(
                id = id, projectId = meta.projectId, displayName = name.substringBeforeLast('.'),
                originalFileName = name, format = format.name, sizeBytes = tmp.length(),
                originalHash = repo(id).snapshot(v1).baseFileHash,
                front = meta.front, tunnel = meta.tunnel, discipline = meta.discipline,
                headCommitId = v1.id, createdAt = now, updatedAt = now, syncState = SyncState.PENDING_UPLOAD,
            )
            db.withTransaction {
                db.documents().insert(doc)
                recordVersion(id, v1)
                enqueueCommitUpload(id, v1)
            }
            audit(user, "IMPORT", id, "$name (${format.name}, ${tmp.length()} B)")
            doc
        } finally {
            tmp.delete()
        }
    }

    /** Carga el modelo CAD desde el original inmutable, en streaming y con progreso. */
    suspend fun loadModel(doc: DocumentEntity, onProgress: (Double) -> Unit): CadDocument = withContext(Dispatchers.IO) {
        val format = CadFormat.valueOf(doc.format)
        val repo = repo(doc.id)
        when (format) {
            CadFormat.DXF_ASCII -> repo.objects.open(doc.originalHash).use { stream ->
                // DXF ≤ R2004 usa ANSI_1252; R2007+ es UTF-8. Se prueba UTF-8 (compatible con ASCII).
                DxfParser(totalChars = doc.sizeBytes, onProgress = onProgress)
                    .parse(InputStreamReader(stream, Charsets.UTF_8))
            }
            else -> {
                val importer = importers.firstOrNull { format in it.supported }
                    ?: throw UnsupportedFormatException(format, hintFor(format))
                repo.objects.open(doc.originalHash).use { importer.import(it, doc.sizeBytes, onProgress) }
            }
        }
    }

    private fun hintFor(f: CadFormat) = when (f) {
        CadFormat.DWG -> "requiere el motor DWG (ODA Drawings SDK) — disponible en la edición Enterprise. " +
            "Alternativa: exporte a DXF desde AutoCAD/BricsCAD/LibreCAD."
        CadFormat.DXF_BINARY -> "guarde el DXF en formato ASCII."
        CadFormat.IFC -> "requiere el módulo BIM (IfcOpenShell/web-ifc), fase 3 del roadmap."
        else -> "previsto en el roadmap (docs/05-roadmap.md)."
    }

    private val sessions = HashMap<String, EditSession>()
    private val _restored = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Emite el docId cuando su estado de trabajo cambia desde fuera del visor (p.ej. restauración). */
    val restored = _restored.asSharedFlow()

    /**
     * Sesión de edición ÚNICA por documento (un solo journal por archivo, compartida
     * entre visor e historial). Recupera automáticamente cambios tras un cierre inesperado.
     */
    fun openSession(docId: String): EditSession = synchronized(sessions) {
        sessions.getOrPut(docId) {
            val r = repo(docId)
            val head = r.head() ?: error("Documento sin versiones")
            EditSession(File(File(docsRoot, docId), "session"), r.snapshot(head))
        }
    }

    /** Persiste el estado de trabajo (la sesión sigue disponible para otras pantallas). */
    fun releaseSession(docId: String) = synchronized(sessions) { sessions[docId]?.autosave() }

    /** Crea una versión inmutable firmada a partir del estado de trabajo. */
    suspend fun commitVersion(docId: String, session: EditSession, message: String, user: String, branch: String = VersionRepository.MAIN): Commit =
        withContext(Dispatchers.IO) {
            session.autosave()
            val c = repo(docId).commit(session.snapshot, user, message, branch, signer = { runCatching { CryptoManager.sign(it) }.getOrNull() ?: "" })
            session.markCommitted()
            db.withTransaction {
                recordVersion(docId, c)
                enqueueCommitUpload(docId, c)
                db.documents().get(docId)?.let {
                    db.documents().update(it.copy(currentVersionLabel = c.label, headCommitId = c.id,
                        updatedAt = System.currentTimeMillis(), syncState = SyncState.PENDING_UPLOAD))
                }
            }
            audit(user, "COMMIT", docId, "${c.label}: $message")
            c
        }

    suspend fun restore(docId: String, commitId: String, session: EditSession, user: String): Commit = withContext(Dispatchers.IO) {
        val c = repo(docId).restore(commitId, user)
        session.reset(repo(docId).snapshot(c))
        db.withTransaction {
            recordVersion(docId, c); enqueueCommitUpload(docId, c)
            db.documents().get(docId)?.let { db.documents().update(it.copy(currentVersionLabel = c.label, headCommitId = c.id)) }
        }
        audit(user, "RESTORE", docId, "→ ${c.label}")
        _restored.tryEmit(docId)
        c
    }

    suspend fun diff(docId: String, from: String, to: String): SnapshotDiff = withContext(Dispatchers.IO) { repo(docId).diff(from, to) }

    suspend fun renameBatch(renames: Map<String, String>, user: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val docs = db.documents().getAll(renames.keys.toList())
            db.documents().updateAll(docs.map { it.copy(displayName = renames.getValue(it.id), updatedAt = now) })
        }
        audit(user, "RENAME", null, renames.entries.joinToString { "${it.key}→${it.value}" })
    }

    suspend fun tag(docId: String, tag: String) {
        db.tags().insertTag(TagEntity(tag)); db.tags().tag(DocumentTagEntity(docId, tag))
    }

    suspend fun audit(user: String, action: String, docId: String?, detail: String, lat: Double? = null, lon: Double? = null) =
        db.audit().log(AuditEntity(timestamp = System.currentTimeMillis(), user = user, action = action,
            documentId = docId, detail = detail, lat = lat, lon = lon))

    private suspend fun recordVersion(docId: String, c: Commit) = db.versions().upsert(
        VersionEntity(c.id, docId, c.label, c.branch, c.author, c.message, c.timestamp,
            c.parents.joinToString(","), signed = !c.signature.isNullOrEmpty())
    )

    /** Outbox: los objetos faltantes en el servidor se calculan en el SyncWorker (sync diferencial). */
    private suspend fun enqueueCommitUpload(docId: String, c: Commit) =
        db.syncQueue().enqueue(SyncQueueEntity(documentId = docId, kind = "COMMIT", ref = c.id, createdAt = System.currentTimeMillis()))
}
