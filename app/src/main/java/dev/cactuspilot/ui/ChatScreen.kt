package dev.cactuspilot.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenModels: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val last = vm.messages.lastOrNull()

    LaunchedEffect(vm.messages.size, last?.text?.length) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.lastIndex)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attachImage(uri)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🌵 CactusPilot") },
                actions = {
                    TextButton(onClick = { vm.speakReplies = !vm.speakReplies }) {
                        Text(if (vm.speakReplies) "🔊 On" else "🔈 Off")
                    }
                    TextButton(onClick = vm::clearChat, enabled = !vm.busy) { Text("Clear") }
                    TextButton(onClick = onOpenModels) { Text("Models") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!vm.nativeAvailable) {
                Notice("Native AI engine unavailable on this device (arm64 phone required).", null)
            } else if (vm.activeChat == null) {
                Notice("No chat model yet. Download one to start.", "Open Models" to onOpenModels)
            }
            vm.banner?.let { Notice(it, "Dismiss" to vm::dismissBanner) }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.messages, key = { it.id }) { Bubble(it) }
            }

            vm.pendingImage?.let { path ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = File(path), contentDescription = "Attached image",
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    TextButton(onClick = vm::removeImage) { Text("Remove") }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("🖼") }
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (vm.recording) "Listening…" else "Message") },
                    maxLines = 4,
                )
                when {
                    vm.transcribing -> CircularProgressIndicator(Modifier.size(24.dp))
                    else -> TextButton(onClick = {
                        if (vm.recording) {
                            vm.stopRecordingAndSend()
                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            vm.startRecording()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) { Text(if (vm.recording) "⏹" else "🎤") }
                }
                if (vm.busy) {
                    Button(onClick = vm::stopGeneration) { Text("Stop") }
                } else {
                    Button(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) { Text("Send") }
                }
            }
        }
    }

    vm.pendingConfirm?.let { pc ->
        AlertDialog(
            onDismissRequest = { vm.resolveConfirmation(false) },
            title = { Text("Allow action?") },
            text = { Text("${pc.tool}\n${pc.args}") },
            confirmButton = { TextButton(onClick = { vm.resolveConfirmation(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { vm.resolveConfirmation(false) }) { Text("Deny") } },
        )
    }
}

@Composable
private fun Notice(text: String, action: Pair<String, () -> Unit>?) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp, 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}

@Composable
private fun Bubble(m: UiMessage) {
    val mine = m.role == "user"
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when {
        m.kind == Kind.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        m.kind == Kind.TOOL -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        mine -> scheme.primaryContainer to scheme.onPrimaryContainer
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp)).background(bg).padding(12.dp),
        ) {
            if (m.imagePath != null) {
                AsyncImage(
                    model = File(m.imagePath), contentDescription = "Image",
                    modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.height(6.dp))
            }
            if (m.text.isEmpty()) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
            } else {
                Text(
                    m.text, color = fg,
                    fontFamily = if (m.kind == Kind.TOOL) FontFamily.Monospace else FontFamily.Default,
                    fontSize = if (m.kind == Kind.TOOL) 12.sp else 16.sp,
                )
            }
            m.stats?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = fg.copy(alpha = 0.6f), fontSize = 11.sp)
            }
        }
    }
}
