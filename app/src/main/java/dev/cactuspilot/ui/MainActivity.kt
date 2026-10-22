package dev.cactuspilot.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cactuspilot.pilotApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pilotApp.updater.checkAsync(manual = false) // throttled, silent
        setContent {
            CactusTheme {
                var screen by rememberSaveable { mutableStateOf("chat") }
                val vm: ChatViewModel = viewModel()
                BackHandler(enabled = screen != "chat") { screen = "chat" }
                when (screen) {
                    "models" -> ModelsScreen(onBack = { screen = "chat" })
                    "settings" -> SettingsScreen(vm = vm, onBack = { screen = "chat" })
                    else -> ChatScreen(
                        vm = vm,
                        onOpenModels = { screen = "models" },
                        onOpenSettings = { screen = "settings" },
                    )
                }
            }
        }
    }
}
