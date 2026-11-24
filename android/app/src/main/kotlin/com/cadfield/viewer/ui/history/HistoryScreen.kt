package com.cadfield.viewer.ui.history

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cadfield.core.versioning.SnapshotDiff
import com.cadfield.viewer.container
import com.cadfield.viewer.data.db.VersionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val docId = MutableStateFlow<String?>(null)
    val versions = docId.flatMapLatest { it?.let(c.documents::observeVersions) ?: emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val diff = MutableStateFlow<Pair<String, SnapshotDiff>?>(null)
    val info = MutableStateFlow<String?>(null)

    fun load(id: String) { docId.value = id }

    fun compare(from: VersionEntity, to: VersionEntity) = viewModelScope.launch {
        diff.value = "${from.label} → ${to.label}" to c.documents.diff(from.documentId, from.commitId, to.commitId)
    }

    /** Restaurar = nuevo commit con el contenido antiguo (el historial no se reescribe). */
    fun restore(v: VersionEntity) = viewModelScope.launch {
        val session = withContext(Dispatchers.IO) { c.documents.openSession(v.documentId) }
        val commit = c.documents.restore(v.documentId, v.commitId, session, c.currentUser)
        info.value = "Restaurado: nueva versión ${commit.label}"
    }

    fun branch(v: VersionEntity, name: String) = viewModelScope.launch(Dispatchers.IO) {
        info.value = runCatching { c.documents.repo(v.documentId).createBranch(name); "Rama '$name' creada" }
            .getOrElse { it.message }
    }

    fun mergeBranch(documentId: String, source: String) = viewModelScope.launch(Dispatchers.IO) {
        info.value = runCatching {
            val (commit, conflicts) = c.documents.repo(documentId).merge(source, com.cadfield.core.versioning.VersionRepository.MAIN, c.currentUser)
            "Fusión ${commit.label}" + if (conflicts.isNotEmpty()) " — ${conflicts.size} conflictos conservados para revisión" else ""
        }.getOrElse { it.message }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(docId: String, onBack: () -> Unit, vm: HistoryViewModel = viewModel()) {
    LaunchedEffect(docId) { vm.load(docId) }
    val versions by vm.versions.collectAsStateWithLifecycle()
    val diff by vm.diff.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<VersionEntity?>(null) }
    var branchFor by remember { mutableStateOf<VersionEntity?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Historial de versiones") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            info?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary) }
            Text(
                if (selected == null) "Toque una versión para compararla con otra" else "Comparar ${selected!!.label} con…",
                Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium,
            )
            LazyColumn {
                items(versions, key = { it.commitId }) { v ->
                    ListItem(
                        modifier = Modifier.clickable {
                            val s = selected
                            if (s == null) selected = v else { vm.compare(s, v); selected = null }
                        },
                        overlineContent = { Text("${v.branch} · ${fmt.format(Date(v.timestamp))}") },
                        headlineContent = { Text("${v.label}  ${v.message}") },
                        supportingContent = { Text("${v.author}${if (v.parentIds.contains(',')) " · fusión" else ""}") },
                        leadingContent = { if (v.signed) Icon(Icons.Default.Verified, "Firmada") },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { branchFor = v }) { Icon(Icons.Default.CallSplit, "Crear rama") }
                                IconButton(onClick = { vm.restore(v) }) { Icon(Icons.Default.Restore, "Restaurar") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    diff?.let { (title, d) ->
        AlertDialog(onDismissRequest = { vm.diff.value = null }, icon = { Icon(Icons.Default.CompareArrows, null) },
            title = { Text(title) },
            text = { Column {
                if (d.isEmpty) Text("Sin diferencias")
                if (d.baseChanged) Text("• El plano base cambió")
                Text("• ${d.added.size} observaciones añadidas")
                Text("• ${d.modified.size} modificadas")
                Text("• ${d.removed.size} eliminadas")
                d.added.take(10).forEach { Text("  + ${it.type} — ${it.author}", style = MaterialTheme.typography.labelSmall) }
            } },
            confirmButton = { TextButton(onClick = { vm.diff.value = null }) { Text("Cerrar") } })
    }

    branchFor?.let { v ->
        var name by remember { mutableStateOf("campo-") }
        AlertDialog(onDismissRequest = { branchFor = null }, title = { Text("Nueva rama desde ${v.branch}") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Nombre (p.ej. campo-frente-norte)") }) },
            confirmButton = { Button(onClick = { vm.branch(v, name); branchFor = null }) { Text("Crear") } },
            dismissButton = { TextButton(onClick = { vm.mergeBranch(v.documentId, v.branch); branchFor = null }) { Text("Fusionar '${v.branch}' en principal") } })
    }
}
