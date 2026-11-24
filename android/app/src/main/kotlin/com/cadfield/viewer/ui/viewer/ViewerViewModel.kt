package com.cadfield.viewer.ui.viewer

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cadfield.core.cad.analysis.DrawingAnalyzer
import com.cadfield.core.cad.analysis.Finding
import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.cad.geom.Vec3
import com.cadfield.core.cad.measure.Measurements
import com.cadfield.core.cad.model.CadEntity
import com.cadfield.core.cad.model.Layer
import com.cadfield.core.versioning.AnnotationRecord
import com.cadfield.core.versioning.EditOp
import com.cadfield.core.versioning.EditSession
import com.cadfield.viewer.container
import com.cadfield.viewer.data.AnnotationKind
import com.cadfield.viewer.data.AnnotationPayload
import com.cadfield.viewer.data.Pt
import com.cadfield.viewer.data.db.DocumentEntity
import com.cadfield.viewer.data.newAnnotation
import com.cadfield.viewer.export.Exporters
import com.cadfield.viewer.export.ReportInfo
import com.cadfield.viewer.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Tool { PAN, SELECT, DISTANCE, POLYLINE, AREA, SLOPE, COORDINATE, NOTE, CLOUD, ARROW }

data class LayerUi(val layer: Layer, val visible: Boolean, val alpha: Float, val count: Int)

data class MeasureUi(val tool: Tool, val points: List<Vec3>, val text: String)

data class ViewerState(
    val doc: DocumentEntity? = null,
    val loadingProgress: Float? = 0f,
    /** Etapa sin progreso medible (p.ej. conversión DWG nativa). */
    val loadingStage: String? = null,
    val error: String? = null,
    val render: RenderModel? = null,
    val layers: List<LayerUi> = emptyList(),
    val layerQuery: String = "",
    val tool: Tool = Tool.PAN,
    val measure: MeasureUi? = null,
    val selected: CadEntity? = null,
    val hiddenHandles: Set<String> = emptySet(),
    val annotations: List<AnnotationRecord> = emptyList(),
    val pendingNoteAt: Vec2? = null,
    val message: String? = null,
    val lastAutosave: Long? = null,
    val dirty: Boolean = false,
    val findings: List<Finding> = emptyList(),
)

class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _state = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = _state.asStateFlow()
    private var session: EditSession? = null
    private val user get() = c.currentUser

    fun open(docId: String) {
        if (_state.value.doc?.id == docId) return
        viewModelScope.launch {
            try {
                val doc = withContext(Dispatchers.IO) { c.db.documents().get(docId) } ?: error("Documento no encontrado")
                _state.update { it.copy(doc = doc, loadingProgress = 0f) }
                val s = withContext(Dispatchers.IO) { c.documents.openSession(docId) }
                session = s
                val model = c.documents.loadModel(doc,
                    onStage = { stage -> _state.update { it.copy(loadingStage = stage) } },
                ) { p -> _state.update { it.copy(loadingProgress = p.toFloat(), loadingStage = null) } }
                val render = withContext(Dispatchers.Default) { RenderModel(model) }
                val counts = model.entities.groupingBy { it.layer }.eachCount()
                val hiddenLayers = s.snapshot.metadata["hidden.layers"]?.split('\u0001')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
                _state.update {
                    it.copy(
                        render = render, loadingProgress = null, loadingStage = null,
                        layers = model.layers.values.map { l -> LayerUi(l, l.visible && !l.frozen && l.name !in hiddenLayers, 1f, counts[l.name] ?: 0) }
                            .sortedBy { l -> l.layer.name },
                        hiddenHandles = s.snapshot.metadata["hidden.handles"]?.split(',')?.filter { h -> h.isNotEmpty() }?.toSet().orEmpty(),
                        annotations = s.snapshot.annotations.values.filter { a -> !a.deleted },
                        message = if (s.recoveredOps > 0) "Se recuperaron ${s.recoveredOps} cambios no guardados tras un cierre inesperado" else null,
                        dirty = s.dirty,
                    )
                }
                launch(Dispatchers.Default) { _state.update { it.copy(findings = DrawingAnalyzer(model).findIssues()) } }
                startAutosave()
                launch {
                    c.documents.restored.collect { id ->
                        if (id != docId) return@collect
                        val fresh = withContext(Dispatchers.IO) { c.db.documents().get(docId) }
                        _state.update {
                            it.copy(annotations = s.snapshot.annotations.values.filter { a -> !a.deleted },
                                doc = fresh ?: it.doc, message = "Versión restaurada")
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString(), loadingProgress = null, loadingStage = null) }
            }
        }
    }

    /** Auto Save cada 30 segundos (además del journal por operación, que es inmediato). */
    private fun startAutosave() = viewModelScope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(AUTOSAVE_MS)
            if (session?.autosave() == true) _state.update { it.copy(lastAutosave = System.currentTimeMillis(), dirty = false) }
        }
    }

    // ---------------- Capas ----------------
    fun toggleLayer(name: String) = updateLayers { if (it.layer.name == name) it.copy(visible = !it.visible) else it }
    fun setAllLayers(visible: Boolean) = updateLayers { it.copy(visible = visible) }
    fun setLayerAlpha(name: String, a: Float) = _state.update { s -> s.copy(layers = s.layers.map { if (it.layer.name == name) it.copy(alpha = a) else it }) }
    fun setLayerQuery(q: String) = _state.update { it.copy(layerQuery = q) }

    private fun updateLayers(f: (LayerUi) -> LayerUi) {
        _state.update { s -> s.copy(layers = s.layers.map(f)) }
        val hidden = _state.value.layers.filter { !it.visible }.joinToString("\u0001") { it.layer.name }
        execute(EditOp.SetMeta("hidden.layers", hidden))
    }

    // ---------------- Herramientas ----------------
    fun setTool(t: Tool) = _state.update { it.copy(tool = t, measure = null, selected = null, pendingNoteAt = null) }

    /** Toque en el plano. [tolWorld] = radio de captura en unidades del dibujo. */
    fun onTap(world: Vec2, tolWorld: Double) {
        val s = _state.value
        val render = s.render ?: return
        val snapped = snap(render, world, tolWorld)
        when (s.tool) {
            Tool.PAN -> Unit
            Tool.SELECT -> _state.update { it.copy(selected = pick(render, world, tolWorld)) }
            Tool.COORDINATE -> {
                val p = snapped ?: Vec3(world.x, world.y)
                _state.update { it.copy(measure = MeasureUi(s.tool, listOf(p), "X %.3f  Y %.3f  Z %.3f".format(p.x, p.y, p.z))) }
            }
            Tool.NOTE, Tool.ARROW, Tool.CLOUD -> _state.update { it.copy(pendingNoteAt = (snapped?.xy() ?: world)) }
            else -> {
                val pts = (s.measure?.points.orEmpty() + (snapped ?: Vec3(world.x, world.y)))
                val max = if (s.tool == Tool.DISTANCE || s.tool == Tool.SLOPE) 2 else Int.MAX_VALUE
                val use = if (pts.size > max) pts.takeLast(1) else pts
                _state.update { it.copy(measure = MeasureUi(s.tool, use, describe(s.tool, use))) }
            }
        }
    }

    private fun describe(tool: Tool, pts: List<Vec3>): String {
        val u = _state.value.render?.doc?.units?.name?.lowercase() ?: ""
        val p2 = pts.map { it.xy() }
        return when (tool) {
            Tool.DISTANCE -> if (pts.size < 2) "Toque el segundo punto" else
                "Distancia %.3f %s  ·  3D %.3f  ·  ΔZ %.3f".format(Measurements.distance2D(p2[0], p2[1]), u,
                    Measurements.distance3D(pts[0], pts[1]), Measurements.elevationDifference(pts[0], pts[1]))
            Tool.SLOPE -> if (pts.size < 2) "Toque el segundo punto (con cota)" else
                Measurements.slope(pts[0], pts[1]).let { "Pendiente %.2f %%  ·  %.2f°  ·  ΔZ %.3f".format(it.percent, it.degrees, it.deltaZ) }
            Tool.POLYLINE -> "Longitud acumulada %.3f %s (%d puntos)".format(Measurements.polylineLength(p2), u, pts.size)
            Tool.AREA -> if (pts.size < 3) "Mínimo 3 puntos" else
                "Área %.3f %s²  ·  Perímetro %.3f".format(Measurements.polygonArea(p2), u, Measurements.perimeter(p2))
            else -> ""
        }
    }

    private fun snap(render: RenderModel, p: Vec2, tol: Double): Vec3? =
        render.index.queryPoint(p, tol)
            .flatMap { render.keyPoints(render.doc.entities[it.entityIndex]) }
            .filter { it.xy().distanceTo(p) <= tol }
            .minByOrNull { it.xy().distanceTo(p) }

    private fun pick(render: RenderModel, p: Vec2, tol: Double): CadEntity? {
        val visible = _state.value.layers.filter { it.visible }.map { it.layer.name }.toSet()
        return render.index.queryPoint(p, tol)
            .filter { it.layer in visible }
            .minByOrNull { item ->
                var best = Double.MAX_VALUE
                val s = item.segments; val o = render.origin
                var k = 0
                while (k < s.size) {
                    val d = Measurements.pointSegmentDistance(p, Vec2(s[k] + o.x, s[k + 1] + o.y), Vec2(s[k + 2] + o.x, s[k + 3] + o.y))
                    if (d < best) best = d
                    k += 4
                }
                best
            }?.let { render.doc.entities[it.entityIndex] }
    }

    /** Guarda la medición actual como anotación trazable. */
    fun saveMeasure() {
        val m = _state.value.measure ?: return
        val kind = when (m.tool) { Tool.AREA -> AnnotationKind.MEASURE_AREA; Tool.POLYLINE -> AnnotationKind.MEASURE_POLYLINE; else -> AnnotationKind.MEASURE_DISTANCE }
        addAnnotation(kind, AnnotationPayload(points = m.points.map { Pt(it.x, it.y) }, text = m.text))
        _state.update { it.copy(measure = null, message = "Medición guardada como observación") }
    }

    fun clearMeasure() = _state.update { it.copy(measure = null) }

    fun addNote(text: String, symbol: String?, photoHash: String? = null) {
        val at = _state.value.pendingNoteAt ?: return
        val kind = when (_state.value.tool) { Tool.CLOUD -> AnnotationKind.CLOUD; Tool.ARROW -> AnnotationKind.ARROW; else -> if (photoHash != null) AnnotationKind.PHOTO else AnnotationKind.TEXT }
        val gps = lastLocation()
        addAnnotation(kind, AnnotationPayload(points = listOf(Pt(at.x, at.y)), text = text, symbol = symbol,
            lat = gps?.latitude, lon = gps?.longitude, gpsAccuracyM = gps?.accuracy, mediaHash = photoHash))
        _state.update { it.copy(pendingNoteAt = null) }
    }

    fun cancelNote() = _state.update { it.copy(pendingNoteAt = null) }

    /** Guarda una foto de campo en el almacén inmutable y devuelve su hash. */
    suspend fun storePhoto(file: File): String? = withContext(Dispatchers.IO) {
        val doc = _state.value.doc ?: return@withContext null
        file.inputStream().use { c.documents.repo(doc.id).objects.put(it) }.also { file.delete() }
    }

    fun deleteAnnotation(id: String) {
        execute(EditOp.Delete(id, user, System.currentTimeMillis()))
    }

    fun hideSelected() {
        val e = _state.value.selected ?: return
        val hidden = _state.value.hiddenHandles + e.handle
        _state.update { it.copy(hiddenHandles = hidden, selected = null) }
        execute(EditOp.SetMeta("hidden.handles", hidden.joinToString(",")))
    }

    fun showAllObjects() {
        _state.update { it.copy(hiddenHandles = emptySet()) }
        execute(EditOp.SetMeta("hidden.handles", ""))
    }

    private fun addAnnotation(kind: AnnotationKind, payload: AnnotationPayload) =
        execute(EditOp.Upsert(newAnnotation(kind, payload, user)))

    private fun execute(op: EditOp) {
        val s = session ?: return
        viewModelScope.launch(Dispatchers.IO) {
            s.execute(op) // journal + fsync antes de reflejarlo en la UI
            _state.update { it.copy(annotations = s.snapshot.annotations.values.filter { a -> !a.deleted }, dirty = true) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? = runCatching {
        val lm = getApplication<Application>().getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()

    // ---------------- Versiones / exportación ----------------
    fun commit(message: String) {
        val s = session ?: return
        val doc = _state.value.doc ?: return
        viewModelScope.launch {
            val commit = c.documents.commitVersion(doc.id, s, message.ifBlank { "Observación de campo" }, user)
            _state.update { it.copy(doc = doc.copy(currentVersionLabel = commit.label), dirty = false, message = "Versión ${commit.label} creada") }
            SyncScheduler.syncNow(getApplication())
        }
    }

    fun export(pdf: Boolean) {
        val st = _state.value
        val doc = st.doc ?: return
        val model = st.render?.doc ?: return
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                val project = c.db.projects().get(doc.projectId)?.name ?: ""
                val hidden = st.layers.filter { !it.visible }.map { it.layer.name }.toSet()
                buildList {
                    if (pdf) add(Exporters.pdf(getApplication(), model, hidden, st.annotations,
                        ReportInfo(project, doc.displayName, doc.currentVersionLabel, user,
                            runCatching { com.cadfield.viewer.security.CryptoManager.sign(doc.originalHash + doc.currentVersionLabel) }.getOrNull())))
                    add(Exporters.csv(getApplication(), st.annotations, doc.displayName))
                }
            }
            c.documents.audit(user, "EXPORT", doc.id, files.joinToString { it.name })
            Exporters.share(getApplication(), files)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        _state.value.doc?.let { c.documents.releaseSession(it.id) } // autosave final
        super.onCleared()
    }

    companion object { const val AUTOSAVE_MS = 30_000L }
}
