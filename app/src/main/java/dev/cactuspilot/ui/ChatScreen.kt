package dev.cactuspilot.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import dev.cactuspilot.ai.ModelCatalog
import dev.cactuspilot.ai.ModelRole
import dev.cactuspilot.pilotApp
import dev.cactuspilot.update.UpdateState
import java.io.File

private val SUGGESTIONS = listOf(
    "Explain how a transformer language model works",
    "Give me a 3-day workout plan for beginners",
    "Write a Python function to merge two sorted lists",
    "Set a timer for 10 minutes",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenModels: () -> Unit, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val active by vm.modelStore.active.collectAsState()
    val chatSpec = ModelCatalog.byId(active[ModelRole.CHAT])
    val updateState by ctx.pilotApp.updater.state.collectAsState()

    var input by remember { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val listState = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val lastLen = vm.messages.lastOrNull()?.text?.length

    // Keep the newest text in view while streaming, when messages are added, and when the keyboard opens.
    LaunchedEffect(vm.messages.size, lastLen, vm.status, imeBottom) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.size)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.attachImage(uri)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { vm.attachImage(it) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
    }

    fun submit(text: String) {
        vm.send(text)
        input = ""
    }

    Scaffold(
        // safeDrawing includes the keyboard, so the composer always sits above it.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CactusPilot", style = MaterialTheme.typography.titleLarge)
                        Text(
                            chatSpec?.title ?: "No model — tap to choose",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onOpenModels),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::newChat, enabled = !vm.busy) { Icon(Icons.Default.Add, "New chat") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!vm.nativeAvailable) {
                Notice("Native AI engine unavailable on this device (arm64 phone required).", null)
            }
            vm.banner?.let { Notice(it, "Dismiss" to vm::dismissBanner) }
            (updateState as? UpdateState.Available)?.let { u ->
                Notice("Update available: ${u.info.versionName}", "Details" to onOpenSettings)
            }

            if (vm.messages.isEmpty()) {
                EmptyState(
                    hasModel = chatSpec != null,
                    onOpenModels = onOpenModels,
                    onSuggestion = ::submit,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                val lastAssistantId = vm.messages.lastOrNull { it.role == "assistant" }?.id
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(vm.messages, key = { it.id }) { m ->
                        MessageItem(
                            m = m,
                            status = if (m.id == lastAssistantId && vm.busy) vm.status else null,
                            canRegenerate = m.id == lastAssistantId && !vm.busy && m.kind == Kind.TEXT,
                            onCopy = { clipboard.setText(AnnotatedString(it)) },
                            onRegenerate = vm::regenerate,
                        )
                    }
                    item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
                }
            }

            Composer(
                input = input,
                onInput = { input = it },
                vm = vm,
                attachMenu = attachMenu,
                onAttachMenu = { attachMenu = it },
                onPickPhoto = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onCamera = {
                    val uri = vm.newCameraUri()
                    cameraUri = uri
                    takePicture.launch(uri)
                },
                onMic = {
                    if (vm.recording) {
                        vm.stopRecordingAndSend()
                    } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.startRecording()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onSend = { submit(input) },
            )
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
private fun EmptyState(
    hasModel: Boolean,
    onOpenModels: () -> Unit,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🌵", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text("How can I help?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Private, on-device AI. Ask, attach a photo, or talk.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (!hasModel) {
            Button(onClick = onOpenModels) { Text("Download a model to start") }
        } else {
            for (s in SUGGESTIONS) {
                AssistChip(
                    onClick = { onSuggestion(s) },
                    label = { Text(s) },
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInput: (String) -> Unit,
    vm: ChatViewModel,
    attachMenu: Boolean,
    onAttachMenu: (Boolean) -> Unit,
    onPickPhoto: () -> Unit,
    onCamera: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            vm.pendingImage?.let { path ->
                Row(Modifier.padding(start = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = File(path), contentDescription = "Attached image",
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    IconButton(onClick = vm::removeImage) { Icon(Icons.Default.Close, "Remove image") }
                }
            }
            TextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        when {
                            vm.recording -> "Listening… tap ⏹ when done"
                            vm.transcribing -> "Transcribing…"
                            else -> "Message CactusPilot"
                        },
                    )
                },
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { onAttachMenu(true) }) { Icon(Icons.Default.Add, "Attach") }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { onAttachMenu(false) }) {
                        DropdownMenuItem(
                            text = { Text("🖼  Photo library") },
                            onClick = { onAttachMenu(false); onPickPhoto() },
                        )
                        DropdownMenuItem(
                            text = { Text("📷  Camera") },
                            onClick = { onAttachMenu(false); onCamera() },
                        )
                    }
                }
                FilterChip(
                    selected = vm.settings.webSearch,
                    onClick = { vm.settings.updateWebSearch(!vm.settings.webSearch) },
                    label = { Text("🌐 Web") },
                )
                Spacer(Modifier.weight(1f))
                if (vm.transcribing) {
                    CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(22.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = onMic) { Text(if (vm.recording) "⏹" else "🎤", fontSize = 20.sp) }
                }
                if (vm.busy) {
                    FilledIconButton(onClick = vm::stopGeneration) { Icon(Icons.Default.Close, "Stop") }
                } else {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = input.isNotBlank() || vm.pendingImage != null,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: Pair<String, () -> Unit>?) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp, 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}

@Composable
private fun MessageItem(
    m: UiMessage,
    status: String?,
    canRegenerate: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    when {
        m.kind == Kind.TOOL -> Card(
            colors = CardDefaults.cardColors(containerColor = scheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                m.text, Modifier.padding(10.dp), color = scheme.onTertiaryContainer,
                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            )
        }

        m.kind == Kind.ERROR -> Card(
            colors = CardDefaults.cardColors(containerColor = scheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(m.text, Modifier.padding(12.dp), color = scheme.onErrorContainer)
        }

        m.role == "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = scheme.primaryContainer,
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (m.imagePath != null) {
                        AsyncImage(
                            model = File(m.imagePath), contentDescription = "Image",
                            modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    SelectionContainer {
                        Text(m.text, color = scheme.onPrimaryContainer, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        else -> Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text("🌵", fontSize = 16.sp) }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                if (m.text.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            status ?: "Thinking…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    SelectionContainer {
                        MarkdownText(m.text, color = scheme.onSurface, onCopyCode = onCopy)
                    }
                    m.stats?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Row {
                        TextButton(onClick = { onCopy(m.text) }) { Text("Copy") }
                        if (canRegenerate) TextButton(onClick = onRegenerate) { Text("Regenerate") }
                    }
                }
            }
        }
    }
}
