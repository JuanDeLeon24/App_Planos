package com.cadfield.viewer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cadfield.viewer.container
import com.cadfield.viewer.ui.explorer.ExplorerScreen
import com.cadfield.viewer.ui.history.HistoryScreen
import com.cadfield.viewer.ui.theme.CadFieldTheme
import com.cadfield.viewer.ui.viewer.ViewerScreen

class MainActivity : ComponentActivity() {

    /** Archivo recibido de otra app pendiente de importar. */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming = extractUri(intent)
        setContent {
            CadFieldTheme {
                val nav = rememberNavController()
                LaunchedEffect(incoming) {
                    val uri = incoming ?: return@LaunchedEffect
                    Toast.makeText(this@MainActivity, "Importando plano…", Toast.LENGTH_SHORT).show()
                    runCatching { container.documents.importExternal(uri, container.currentUser) }
                        .onSuccess { doc ->
                            nav.navigate("viewer/${doc.id}") { launchSingleTop = true }
                        }
                        .onFailure {
                            Toast.makeText(this@MainActivity, "No se pudo importar: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    incoming = null
                }
                NavHost(nav, startDestination = "explorer") {
                    composable("explorer") { ExplorerScreen(onOpen = { nav.navigate("viewer/$it") }) }
                    composable("viewer/{id}") { e ->
                        val id = e.arguments?.getString("id")!!
                        ViewerScreen(id, onBack = { nav.popBackStack() }, onHistory = { nav.navigate("history/$id") })
                    }
                    composable("history/{id}") { e -> HistoryScreen(e.arguments?.getString("id")!!, onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    /** La app ya estaba abierta (launchMode singleTask) y llega otro archivo. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUri(intent)?.let { incoming = it }
    }

    private fun extractUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
