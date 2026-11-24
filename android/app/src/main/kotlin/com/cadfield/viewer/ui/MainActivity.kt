package com.cadfield.viewer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cadfield.viewer.ui.explorer.ExplorerScreen
import com.cadfield.viewer.ui.history.HistoryScreen
import com.cadfield.viewer.ui.theme.CadFieldTheme
import com.cadfield.viewer.ui.viewer.ViewerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CadFieldTheme {
                val nav = rememberNavController()
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
}
