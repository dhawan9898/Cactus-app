package dev.cactuspilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cactuspilot.ai.ModelCatalog
import dev.cactuspilot.ai.ModelRole
import dev.cactuspilot.ai.ModelSpec
import dev.cactuspilot.ai.ModelState
import dev.cactuspilot.ai.ModelStore
import dev.cactuspilot.pilotApp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(onBack: () -> Unit) {
    val store = LocalContext.current.pilotApp.modelStore
    val states by store.states.collectAsState()
    val active by store.active.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("Chat & vision", style = MaterialTheme.typography.titleMedium) }
            items(ModelCatalog.forRole(ModelRole.CHAT), key = { it.id }) { spec ->
                ModelCard(spec, states[spec.id] ?: ModelState.NotInstalled, active[spec.role] == spec.id, store) {
                    scope.launch { store.download(spec) }
                }
            }
            item { Text("Voice input", style = MaterialTheme.typography.titleMedium) }
            items(ModelCatalog.forRole(ModelRole.SPEECH), key = { it.id }) { spec ->
                ModelCard(spec, states[spec.id] ?: ModelState.NotInstalled, active[spec.role] == spec.id, store) {
                    scope.launch { store.download(spec) }
                }
            }
            item { Text("Models are downloaded from huggingface.co/Cactus-Compute and run fully on-device.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    state: ModelState,
    isActive: Boolean,
    store: ModelStore,
    onDownload: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(spec.title + if (isActive) "  ✓ active" else "", style = MaterialTheme.typography.titleSmall)
            val caps = buildList {
                add("${spec.sizeMb} MB")
                if (spec.vision) add("vision")
            }.joinToString(" · ")
            Text(caps, style = MaterialTheme.typography.labelMedium)
            if (spec.note.isNotEmpty()) Text(spec.note, style = MaterialTheme.typography.bodySmall)

            when (state) {
                is ModelState.Downloading -> {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${state.phase} ${(state.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                is ModelState.Failed -> {
                    Text("Failed: ${state.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onDownload) { Text("Retry") }
                }
                ModelState.NotInstalled -> Button(onClick = onDownload) { Text("Download") }
                ModelState.Installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isActive) Button(onClick = { store.setActive(spec) }) { Text("Use") }
                    OutlinedButton(onClick = { store.delete(spec) }) { Text("Delete") }
                }
            }
        }
    }
}
