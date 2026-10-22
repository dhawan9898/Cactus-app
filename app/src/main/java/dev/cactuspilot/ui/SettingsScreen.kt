package dev.cactuspilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cactuspilot.pilotApp
import dev.cactuspilot.update.UpdateManager
import dev.cactuspilot.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val updater = LocalContext.current.pilotApp.updater
    val update by updater.state.collectAsState()
    val s = vm.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("App updates", style = MaterialTheme.typography.titleMedium)
                    Text("Installed: ${updater.currentVersion} (build ${updater.currentBuild})", style = MaterialTheme.typography.bodyMedium)
                    UpdateSection(update, updater)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Generation", style = MaterialTheme.typography.titleMedium)
                    Text("Creativity (temperature): %.2f".format(s.temperature))
                    Slider(value = s.temperature, onValueChange = { s.updateTemperature(Math.round(it * 20) / 20f) }, valueRange = 0f..1.5f)
                    Text("Max reply length: ${s.maxTokens} tokens")
                    Slider(
                        value = s.maxTokens.toFloat(),
                        onValueChange = { s.updateMaxTokens((it / 64).toInt().coerceAtLeast(1) * 64) },
                        valueRange = 64f..2048f,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Behaviour", style = MaterialTheme.typography.titleMedium)
                    ToggleRow("Let the assistant use device tools", s.toolsEnabled) { s.updateToolsEnabled(it) }
                    ToggleRow("Read replies aloud", s.speakReplies) { s.updateSpeakReplies(it) }
                    OutlinedButton(onClick = vm::clearChat, enabled = !vm.busy) { Text("Clear conversation") }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun UpdateSection(state: UpdateState, updater: UpdateManager) {
    when (state) {
        UpdateState.Idle -> Button(onClick = { updater.checkAsync(manual = true) }) { Text("Check for updates") }
        UpdateState.Checking -> Text("Checking…")
        UpdateState.UpToDate -> {
            Text("You are up to date.")
            OutlinedButton(onClick = { updater.checkAsync(manual = true) }) { Text("Check again") }
        }
        is UpdateState.Available -> {
            Text("New build: ${state.info.versionName}", style = MaterialTheme.typography.titleSmall)
            if (state.info.notes.isNotEmpty()) Text(state.info.notes, style = MaterialTheme.typography.bodySmall)
            Text("Installs over the current app; your models and chats are kept.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { updater.downloadAsync(state.info) }) {
                Text("Download & install (${state.info.sizeBytes / 1_000_000} MB)")
            }
        }
        is UpdateState.Downloading -> {
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            Text("Downloading ${(state.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
        }
        is UpdateState.ReadyToInstall -> {
            Text("Downloaded. If the installer did not open, tap Install.")
            Button(onClick = { updater.install(state.info, state.file) }) { Text("Install") }
        }
        is UpdateState.NeedsInstallPermission -> {
            Text("Allow CactusPilot to install apps in the screen that just opened, come back, then tap Install.")
            Button(onClick = { updater.install(state.info, state.file) }) { Text("Install") }
        }
        is UpdateState.Error -> {
            Text("Update failed: ${state.message}", color = MaterialTheme.colorScheme.error)
            Button(onClick = { updater.checkAsync(manual = true) }) { Text("Retry") }
        }
    }
}
