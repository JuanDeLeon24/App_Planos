package com.cadfield.viewer.ui.viewer

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cadfield.core.cad.geom.Affine2D
import com.cadfield.core.cad.measure.Measurements
import com.cadfield.core.cad.model.CadEntity
import kotlinx.coroutines.launch
import java.io.File

private data class ToolDef(val tool: Tool, val icon: ImageVector, val label: String)

private val tools = listOf(
    ToolDef(Tool.PAN, Icons.Default.PanTool, "Navegar"),
    ToolDef(Tool.SELECT, Icons.Default.TouchApp, "Seleccionar"),
    ToolDef(Tool.DISTANCE, Icons.Default.Straighten, "Distancia"),
    ToolDef(Tool.POLYLINE, Icons.Default.Timeline, "Polilínea"),
    ToolDef(Tool.AREA, Icons.Default.SquareFoot, "Área"),
    ToolDef(Tool.SLOPE, Icons.Default.Landscape, "Pendiente"),
    ToolDef(Tool.COORDINATE, Icons.Default.MyLocation, "Coordenada"),
    ToolDef(Tool.NOTE, Icons.Default.StickyNote2, "Nota"),
    ToolDef(Tool.ARROW, Icons.Default.NorthEast, "Flecha"),
    ToolDef(Tool.CLOUD, Icons.Default.Cloud, "Nube"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(docId: String, onBack: () -> Unit, onHistory: () -> Unit, vm: ViewerViewModel = viewModel()) {
    LaunchedEffect(docId) { vm.open(docId) }
    val st by vm.state.collectAsStateWithLifecycle()
    var view by remember { mutableStateOf<Affine2D?>(null) }
    var rotation by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showCommit by remember { mutableStateOf(false) }
    var showFindings by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(st.message) { st.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) { permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA)) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
                title = {
                    Column {
                        Text(st.doc?.displayName ?: "…", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${st.doc?.currentVersionLabel ?: ""}${if (st.dirty) " · cambios sin versionar" else ""}" +
                                (st.lastAutosave?.let { " · autoguardado" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showLayers = true }) { Icon(Icons.Default.Layers, "Capas") }
                    IconButton(onClick = { showCommit = true }) { Icon(Icons.Default.Save, "Guardar versión") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Más") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Historial de versiones") }, { menu = false; onHistory() }, leadingIcon = { Icon(Icons.Default.History, null) })
                        DropdownMenuItem({ Text("Exportar PDF técnico + CSV") }, { menu = false; vm.export(pdf = true) }, leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) })
                        DropdownMenuItem({ Text("Exportar observaciones (CSV/Excel)") }, { menu = false; vm.export(pdf = false) }, leadingIcon = { Icon(Icons.Default.TableChart, null) })
                        DropdownMenuItem({ Text("Análisis del plano (${st.findings.size})") }, { menu = false; showFindings = true }, leadingIcon = { Icon(Icons.Default.BugReport, null) })
                        DropdownMenuItem({ Text(if (rotation) "Bloquear rotación" else "Permitir rotación") }, { menu = false; rotation = !rotation }, leadingIcon = { Icon(Icons.Default.ScreenRotation, null) })
                        DropdownMenuItem({ Text("Mostrar objetos ocultos") }, { menu = false; vm.showAllObjects() }, leadingIcon = { Icon(Icons.Default.Visibility, null) })
                    }
                },
            )
        },
        bottomBar = {
            LazyRow(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(tools) { t ->
                    FilterChip(selected = st.tool == t.tool, onClick = { vm.setTool(t.tool) },
                        label = { Text(t.label) }, leadingIcon = { Icon(t.icon, null, Modifier.size(18.dp)) })
                }
            }
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().background(Color(0xFF0B1620))) {
            val w = constraints.maxWidth.toDouble(); val h = constraints.maxHeight.toDouble()
            val render = st.render
            LaunchedEffect(render, w, h) { if (render != null && view == null) view = Affine2D.fit(render.focus, w, h) }
            when {
                st.error != null -> ErrorPanel(st.error!!)
                st.loadingProgress != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (st.loadingStage != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { st.loadingProgress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(st.loadingStage ?: "Cargando plano… ${((st.loadingProgress ?: 0f) * 100).toInt()} %", color = Color.White)
                }
                view != null -> CadCanvas(st, view!!, { step -> view = view?.let { step.concat(it) } }, rotation, vm::onTap, Modifier.fillMaxSize())
            }
            // Botón "ajustar a extensión"
            if (render != null) SmallFloatingActionButton(
                onClick = { view = Affine2D.fit(render.focus, w, h) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) { Icon(Icons.Default.FitScreen, "Ajustar") }

            st.measure?.let { m ->
                Card(Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        if (m.tool != Tool.COORDINATE) TextButton(onClick = vm::saveMeasure) { Text("Guardar") }
                        IconButton(onClick = vm::clearMeasure) { Icon(Icons.Default.Close, "Limpiar") }
                    }
                }
            }
            st.selected?.let { e -> PropertiesCard(e, st.render?.doc?.units?.name ?: "", vm::hideSelected, Modifier.align(Alignment.TopStart)) }
        }
    }

    if (showLayers) ModalBottomSheet(onDismissRequest = { showLayers = false }) { LayerPanel(st, vm) }
    if (showCommit) CommitDialog({ showCommit = false }) { vm.commit(it); showCommit = false }
    if (showFindings) FindingsDialog(st) { showFindings = false }
    if (st.pendingNoteAt != null) NoteDialog(vm, onPhoto = { file, text, symbol ->
        scope.launch { vm.addNote(text, symbol, vm.storePhoto(file)) }
    })
}

@Composable
private fun ErrorPanel(msg: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
    Card { Column(Modifier.padding(20.dp)) {
        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
        Text("No se pudo abrir el plano", style = MaterialTheme.typography.titleMedium)
        Text(msg, style = MaterialTheme.typography.bodyMedium)
        Text("El archivo original no fue modificado.", style = MaterialTheme.typography.labelSmall)
    } }
}

@Composable
private fun LayerPanel(st: ViewerState, vm: ViewerViewModel) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Capas (${st.layers.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.setAllLayers(true) }) { Text("Todas") }
            TextButton(onClick = { vm.setAllLayers(false) }) { Text("Ninguna") }
        }
        OutlinedTextField(st.layerQuery, vm::setLayerQuery, Modifier.fillMaxWidth(), placeholder = { Text("Buscar / filtrar capas") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        // Agrupación por prefijo (convención "DISCIPLINA-ELEMENTO", p.ej. GEO-FALLAS, TOP-CURVAS)
        val filtered = st.layers.filter { st.layerQuery.isBlank() || it.layer.name.contains(st.layerQuery, ignoreCase = true) }
        val groups = filtered.groupBy { it.layer.name.substringBefore('-', "General").substringBefore('_') }
        LazyColumn(Modifier.height(420.dp)) {
            groups.forEach { (group, layers) ->
                item { Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
                items(layers, key = { it.layer.name }) { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(Color(AciPalette.resolve(256, null, l.layer.color.aci, l.layer.color.rgb)), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.layer.name, maxLines = 1)
                            Text("${l.count} entidades${if (l.layer.locked) " · bloqueada" else ""}", style = MaterialTheme.typography.labelSmall)
                        }
                        Slider(l.alpha, { vm.setLayerAlpha(l.layer.name, it) }, Modifier.width(90.dp), valueRange = 0.1f..1f)
                        Switch(l.visible, { vm.toggleLayer(l.layer.name) })
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PropertiesCard(e: CadEntity, units: String, onHide: () -> Unit, modifier: Modifier) {
    Card(modifier.padding(8.dp).width(280.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(e::class.simpleName ?: "", style = MaterialTheme.typography.titleSmall)
            Text("Handle ${e.handle} · Capa ${e.layer}", style = MaterialTheme.typography.labelSmall)
            val props = when (e) {
                is CadEntity.Line -> listOf("Longitud" to "%.3f".format(Measurements.distance2D(e.start.xy(), e.end.xy())),
                    "Inicio" to "%.3f, %.3f, %.3f".format(e.start.x, e.start.y, e.start.z), "Fin" to "%.3f, %.3f, %.3f".format(e.end.x, e.end.y, e.end.z))
                is CadEntity.Polyline -> listOf("Vértices" to "${e.vertices.size}", "Cerrada" to if (e.closed) "Sí" else "No",
                    "Longitud" to "%.3f".format(Measurements.polylineLength(e.vertices.map { it.xy() }, e.bulges, e.closed))) +
                    if (e.closed) listOf("Área" to "%.3f".format(Measurements.polygonArea(e.toPolylines(1e-3).first()))) else emptyList()
                is CadEntity.Circle -> listOf("Radio" to "%.3f".format(e.radius), "Área" to "%.3f".format(Math.PI * e.radius * e.radius))
                is CadEntity.Arc -> listOf("Radio" to "%.3f".format(e.radius), "Ángulos" to "%.2f° → %.2f°".format(e.startDeg, e.endDeg))
                is CadEntity.Insert -> listOf("Bloque" to e.blockName) + e.attributes.toList()
                is CadEntity.Text -> listOf("Texto" to e.value)
                else -> emptyList()
            }
            props.forEach { (k, v) -> Row { Text("$k: ", style = MaterialTheme.typography.labelMedium); Text("$v $units".takeIf { k in setOf("Longitud", "Radio") } ?: v) } }
            TextButton(onClick = onHide) { Text("Ocultar objeto") }
        }
    }
}

@Composable
private fun CommitDialog(onDismiss: () -> Unit, onCommit: (String) -> Unit) {
    var msg by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar nueva versión") },
        text = { Column {
            Text("Se creará una versión inmutable y firmada. El plano original no se modifica.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(msg, { msg = it }, label = { Text("Comentario (p.ej. Observación Campo)") })
        } },
        confirmButton = { Button(onClick = { onCommit(msg) }) { Text("Versionar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private val symbols = listOf("FILTRACION", "FISURA", "DESPRENDIMIENTO", "SOSTENIMIENTO", "PUNTO_INSPECCION", "SEGURIDAD")

@Composable
private fun NoteDialog(vm: ViewerViewModel, onPhoto: (File, String, String?) -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf<String?>(null) }
    var photo by remember { mutableStateOf<Pair<File, Uri>?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = photo
        if (ok && p != null) onPhoto(p.first, text, symbol)
    }
    AlertDialog(
        onDismissRequest = vm::cancelNote,
        title = { Text("Observación de campo") },
        text = { Column {
            OutlinedTextField(text, { text = it }, label = { Text("Descripción") }, minLines = 2)
            Text("Símbolo técnico", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(symbols) { s -> FilterChip(symbol == s, { symbol = if (symbol == s) null else s }, { Text(s.lowercase().replace('_', ' ')) }) }
            }
            Text("Se registrará usuario, fecha, hora y GPS.", style = MaterialTheme.typography.labelSmall)
        } },
        confirmButton = { Button(onClick = { vm.addNote(text, symbol) }) { Text("Agregar") } },
        dismissButton = {
            TextButton(onClick = {
                val dir = File(ctx.cacheDir, "media").apply { mkdirs() }
                val f = File.createTempFile("foto", ".jpg", dir)
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                photo = f to uri
                camera.launch(uri)
            }) { Icon(Icons.Default.PhotoCamera, null); Text(" Con foto") }
        },
    )
}

@Composable
private fun FindingsDialog(st: ViewerState, onDismiss: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Análisis del plano") },
    text = {
        if (st.findings.isEmpty()) Text("No se detectaron errores geométricos.")
        else LazyColumn { items(st.findings) { f -> Text("• [${f.severity}] ${f.message}") } }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
)
