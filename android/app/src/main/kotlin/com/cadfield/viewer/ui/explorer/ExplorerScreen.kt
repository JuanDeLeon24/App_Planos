package com.cadfield.viewer.ui.explorer

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cadfield.viewer.container
import com.cadfield.viewer.data.ImportMetadata
import com.cadfield.viewer.data.db.DocumentEntity
import com.cadfield.viewer.data.db.ProjectEntity
import com.cadfield.viewer.data.db.SyncState
import com.cadfield.viewer.sync.SyncScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Filters(val projectId: String? = null, val discipline: String? = null, val front: String? = null, val query: String = "")

@OptIn(ExperimentalCoroutinesApi::class)
class ExplorerViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val filters = MutableStateFlow(Filters())
    val projects = c.documents.observeProjects().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val disciplines = c.documents.observeDisciplines().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pendingSync = c.documents.observePendingSync().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val documents = filters.flatMapLatest { f -> c.documents.observeDocuments(f.projectId, f.discipline, f.front, f.query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val busy = MutableStateFlow<String?>(null)

    fun createProject(name: String) = viewModelScope.launch {
        val p = c.documents.createProject(name, c.currentUser)
        filters.value = filters.value.copy(projectId = p.id)
    }

    fun import(uris: List<Uri>, meta: ImportMetadata) = viewModelScope.launch {
        for ((i, u) in uris.withIndex()) {
            busy.value = "Importando ${i + 1}/${uris.size}…"
            runCatching { c.documents.import(u, meta, c.currentUser) }.onFailure { busy.value = "Error: ${it.message}" }
        }
        busy.value = null
        SyncScheduler.syncNow(getApplication())
    }

    fun toggleSelect(id: String) { selection.value = selection.value.let { if (id in it) it - id else it + id } }

    /** Renombrado masivo con patrón: {proyecto} {disciplina} {frente} {tunel} {n} {nombre} {fecha}. */
    fun renameBatch(pattern: String, docs: List<DocumentEntity>) = viewModelScope.launch {
        val projectNames = projects.value.associate { it.id to it.name }
        val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val map = docs.withIndex().associate { (i, d) ->
            d.id to pattern
                .replace("{proyecto}", projectNames[d.projectId] ?: "")
                .replace("{disciplina}", d.discipline ?: "")
                .replace("{frente}", d.front ?: "")
                .replace("{tunel}", d.tunnel ?: "")
                .replace("{nombre}", d.displayName)
                .replace("{fecha}", date)
                .replace("{n}", (i + 1).toString().padStart(3, '0'))
        }
        c.documents.renameBatch(map, c.currentUser)
        selection.value = emptySet()
    }

    fun syncNow() = SyncScheduler.syncNow(getApplication())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ExplorerScreen(onOpen: (String) -> Unit, vm: ExplorerViewModel = viewModel()) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val disciplines by vm.disciplines.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val pending by vm.pendingSync.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var newProject by remember { mutableStateOf(false) }
    var importUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var rename by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importUris = uris
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CAD Field Viewer Pro") },
                actions = {
                    if (selection.isNotEmpty()) IconButton(onClick = { rename = true }) { Icon(Icons.Default.DriveFileRenameOutline, "Renombrar") }
                    BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) {
                        IconButton(onClick = vm::syncNow) { Icon(Icons.Default.CloudSync, "Sincronizar") }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (projects.isEmpty()) newProject = true else picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(if (projects.isEmpty()) "Crear proyecto" else "Importar planos") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, Modifier.padding(8.dp)) }
            OutlinedTextField(filters.query, { vm.filters.value = filters.copy(query = it) }, Modifier.fillMaxWidth().padding(8.dp),
                placeholder = { Text("Buscar plano, túnel…") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { AssistChip(onClick = { newProject = true }, label = { Text("+ Proyecto") }) }
                items(projects) { p: ProjectEntity ->
                    FilterChip(filters.projectId == p.id, { vm.filters.value = filters.copy(projectId = if (filters.projectId == p.id) null else p.id) }, { Text(p.name) })
                }
                items(disciplines) { d ->
                    FilterChip(filters.discipline == d, { vm.filters.value = filters.copy(discipline = if (filters.discipline == d) null else d) }, { Text(d) },
                        leadingIcon = { Icon(Icons.Default.Category, null, Modifier.size(16.dp)) })
                }
            }
            if (docs.isEmpty()) Text("Sin planos. Importe DXF/DWG/PDF desde el almacenamiento, correo o la nube.", Modifier.padding(24.dp))
            LazyColumn {
                items(docs, key = { it.id }) { d ->
                    ListItem(
                        modifier = Modifier.combinedClickable(
                            onClick = { if (selection.isEmpty()) onOpen(d.id) else vm.toggleSelect(d.id) },
                            onLongClick = { vm.toggleSelect(d.id) },
                        ),
                        leadingContent = {
                            if (d.id in selection) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Default.Architecture, null)
                        },
                        headlineContent = { Text(d.displayName) },
                        overlineContent = { Text(listOfNotNull(d.format, d.discipline, d.front, d.tunnel).joinToString(" · ")) },
                        supportingContent = { Text("${d.currentVersionLabel} · ${"%.1f".format(d.sizeBytes / 1_048_576.0)} MB") },
                        trailingContent = {
                            when (d.syncState) {
                                SyncState.SYNCED -> Icon(Icons.Default.CloudDone, "Sincronizado")
                                SyncState.CONFLICT -> Icon(Icons.Default.Warning, "Conflicto", tint = MaterialTheme.colorScheme.error)
                                SyncState.PENDING_UPLOAD -> Icon(Icons.Default.CloudUpload, "Pendiente")
                                SyncState.LOCAL_ONLY -> Icon(Icons.Default.PhoneAndroid, "Sólo local")
                            }
                        },
                    )
                }
            }
        }
    }

    if (newProject) {
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { newProject = false }, title = { Text("Nuevo proyecto") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre (p.ej. Túnel Línea 2)") }) },
            confirmButton = { Button(onClick = { vm.createProject(name); newProject = false }, enabled = name.isNotBlank()) { Text("Crear") } })
    }

    if (importUris.isNotEmpty()) {
        var front by remember { mutableStateOf("") }
        var tunnel by remember { mutableStateOf("") }
        var discipline by remember { mutableStateOf("") }
        val projectId = filters.projectId ?: projects.firstOrNull()?.id
        AlertDialog(onDismissRequest = { importUris = emptyList() }, title = { Text("Clasificar ${importUris.size} plano(s)") },
            text = { Column {
                Text("Proyecto: ${projects.firstOrNull { it.id == projectId }?.name ?: "—"}")
                OutlinedTextField(discipline, { discipline = it }, label = { Text("Disciplina") })
                OutlinedTextField(front, { front = it }, label = { Text("Frente") })
                OutlinedTextField(tunnel, { tunnel = it }, label = { Text("Túnel / galería") })
                Text("Los archivos originales se guardan como V1.0 inmutable.", style = MaterialTheme.typography.labelSmall)
            } },
            confirmButton = { Button(enabled = projectId != null, onClick = {
                vm.import(importUris, ImportMetadata(projectId!!, front.ifBlank { null }, tunnel.ifBlank { null }, discipline.ifBlank { null }))
                importUris = emptyList()
            }) { Text("Importar") } })
    }

    if (rename) {
        var pattern by remember { mutableStateOf("{proyecto}_{disciplina}_{n}") }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Renombrar ${selection.size} documento(s)") },
            text = { Column {
                OutlinedTextField(pattern, { pattern = it }, label = { Text("Patrón") })
                Text("Variables: {proyecto} {disciplina} {frente} {tunel} {nombre} {fecha} {n}", style = MaterialTheme.typography.labelSmall)
            } },
            confirmButton = { Button(onClick = { vm.renameBatch(pattern, docs.filter { it.id in selection }); rename = false }) { Text("Aplicar") } })
    }
}
