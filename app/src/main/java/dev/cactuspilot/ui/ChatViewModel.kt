package dev.cactuspilot.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cactuspilot.ai.AgentLoop
import dev.cactuspilot.ai.ChatTurn
import dev.cactuspilot.ai.ModelCatalog
import dev.cactuspilot.ai.ModelRole
import dev.cactuspilot.ai.ModelState
import dev.cactuspilot.ai.Tool
import dev.cactuspilot.ai.ToolRouter
import dev.cactuspilot.ai.WebContext
import dev.cactuspilot.pilotApp
import dev.cactuspilot.voice.PcmRecorder
import dev.cactuspilot.voice.Speaker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class Kind { TEXT, TOOL, ERROR }

data class UiMessage(
    val id: Long,
    val role: String,
    val text: String,
    val imagePath: String? = null,
    val kind: Kind = Kind.TEXT,
    val stats: String? = null,
)

class PendingConfirm(val tool: String, val args: String, val answer: CompletableDeferred<Boolean>)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val host = app.pilotApp.engineHost
    val modelStore = app.pilotApp.modelStore
    val settings = app.pilotApp.settings
    private val convStore = ConversationStore(app)
    private val agent = AgentLoop(host, app)
    private val recorder = PcmRecorder()
    private val speaker = Speaker(app)
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L

    val messages = mutableStateListOf<UiMessage>()
    val conversations = mutableStateListOf<ConversationMeta>()
    var activeId by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** What the assistant is doing right now ("Loading model…", "Thinking…"); null once text is streaming. */
    var status by mutableStateOf<String?>(null)
        private set
    var recording by mutableStateOf(false)
        private set
    var transcribing by mutableStateOf(false)
        private set
    var pendingImage by mutableStateOf<String?>(null)
        private set
    var pendingConfirm by mutableStateOf<PendingConfirm?>(null)
        private set
    var banner by mutableStateOf<String?>(null)
        private set

    val nativeAvailable get() = host.nativeAvailable

    init {
        var list = convStore.list()
        if (list.isEmpty()) list = listOf(convStore.create())
        conversations += list
        switchTo(convStore.activeId?.takeIf { a -> list.any { it.id == a } } ?: list.first().id)
    }

    private fun switchTo(id: String) {
        activeId = id
        convStore.activeId = id
        messages.clear()
        messages += convStore.load(id)
        nextId = (messages.maxOfOrNull { it.id } ?: 0L) + 1
        pendingImage = null
    }

    fun dismissBanner() { banner = null }

    /** First-run helper: downloads the default chat and voice models one after the other. */
    fun quickSetup() {
        viewModelScope.launch {
            for (role in ModelRole.entries) {
                val spec = ModelCatalog.defaultFor(role)
                val state = modelStore.states.value[spec.id]
                if (state is ModelState.Installed || state is ModelState.Downloading) continue
                modelStore.download(spec)
            }
        }
    }

    private fun persist() {
        val id = activeId ?: return
        val updated = convStore.save(id, messages.toList()) ?: return
        val i = conversations.indexOfFirst { it.id == id }
        if (i >= 0) conversations[i] = updated
        val sorted = conversations.sortedByDescending { it.updatedAt }
        conversations.clear()
        conversations.addAll(sorted)
    }

    // ---- turns -----------------------------------------------------------------------------

    fun send(text: String) {
        if (busy) return
        val image = pendingImage
        var prompt = text.trim()
        if (prompt.isEmpty() && image != null) prompt = "Describe this image."
        if (prompt.isEmpty()) return
        pendingImage = null

        val spec = modelStore.activeSpec(ModelRole.CHAT)
        // Only hand images to models that can see; otherwise drop with a note.
        val usableImage = if (spec?.vision == true) image else null
        if (image != null && usableImage == null) banner = "The active model cannot read images; sent as text only."

        messages += UiMessage(nextId++, "user", prompt, usableImage)
        generate()
    }

    /** Re-answers the latest user message (drops the previous answer and any tool cards after it). */
    fun regenerate() {
        if (busy) return
        val lastUser = messages.indexOfLast { it.role == "user" && it.kind == Kind.TEXT }
        if (lastUser < 0) return
        while (messages.size > lastUser + 1) messages.removeAt(messages.lastIndex)
        generate()
    }

    private fun generate() {
        val replyId = nextId++
        messages += UiMessage(replyId, "assistant", "")
        busy = true
        status = "Preparing…"

        val history = messages
            .filter { it.kind == Kind.TEXT && it.id != replyId && it.text.isNotEmpty() }
            .map { ChatTurn(it.role, it.text, it.imagePath) }
        val question = history.lastOrNull { it.role == "user" }?.text.orEmpty()

        viewModelScope.launch {
            val sb = StringBuilder()
            var sources: String? = null
            try {
                var web: String? = null
                if (settings.webSearch) {
                    status = "Searching Wikipedia…"
                    val hits = try {
                        withTimeoutOrNull(9_000) { WebContext.search(question) }
                    } catch (e: Exception) {
                        null
                    }
                    if (!hits.isNullOrEmpty()) {
                        web = WebContext.format(hits, LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                        sources = "🌐 " + hits.joinToString(" · ") { it.title }
                    }
                }
                val request = AgentLoop.Request(
                    history = history,
                    useTools = settings.toolsEnabled && ToolRouter.wanted(question),
                    options = settings.genOptions(),
                    contextTokens = settings.contextTokens,
                    webContext = web,
                )
                val result = agent.run(
                    req = request,
                    confirm = ::askConfirmation,
                    onStatus = { s -> main.post { status = s } },
                    onToken = { t ->
                        main.post {
                            if (status != null) status = null
                            sb.append(t)
                            setText(replyId, sb.toString())
                        }
                    },
                    onClear = { main.post { sb.clear(); setText(replyId, "") } },
                    onToolEvent = { e -> main.post { insertBefore(replyId, toolMessage(e.name, e.args, e.result)) } },
                )
                val stats = buildString {
                    append("%.1f tok/s · first token %.1f s".format(result.decodeTps, result.ttftMs / 1000.0))
                    sources?.let { append('\n').append(it) }
                }
                updateMessage(replyId) { it.copy(text = result.text.trim().ifEmpty { "(no response)" }, stats = stats) }
                if (settings.speakReplies) speaker.speak(result.text)
            } catch (e: Exception) {
                updateMessage(replyId) { it.copy(text = e.message ?: "Something went wrong", kind = Kind.ERROR) }
            } finally {
                busy = false
                status = null
                persist()
            }
        }
    }

    fun stopGeneration() {
        host.chat.stop()
        speaker.stop()
    }

    /** Starts a fresh conversation (the current one stays in the list). No-op if the current one is empty. */
    fun newChat() {
        if (busy) return
        if (messages.none { it.text.isNotEmpty() }) return
        persist()
        val meta = convStore.create()
        conversations.add(0, meta)
        switchTo(meta.id)
    }

    fun openConversation(id: String) {
        if (busy || id == activeId) return
        persist()
        switchTo(id)
    }

    fun deleteConversation(id: String) {
        if (busy) return
        convStore.delete(id)
        conversations.removeAll { it.id == id }
        if (id == activeId) {
            if (conversations.isEmpty()) conversations.add(convStore.create())
            switchTo(conversations.first().id)
        }
    }

    fun renameConversation(id: String, title: String) {
        convStore.rename(id, title)
        val i = conversations.indexOfFirst { it.id == id }
        val clean = title.trim().take(60)
        if (i >= 0 && clean.isNotEmpty()) conversations[i] = conversations[i].copy(title = clean)
    }

    // ---- image attach ----------------------------------------------------------------------

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            pendingImage = withContext(Dispatchers.IO) { copyDownscaled(uri) }
            if (pendingImage == null) {
                banner = "Could not read that image."
            } else if (modelStore.activeSpec(ModelRole.CHAT)?.vision != true) {
                banner = "Active model is text-only. Switch to a vision model in Models."
            }
        }
    }

    fun removeImage() { pendingImage = null }

    /** Fresh FileProvider Uri for the camera app to write a photo into. */
    fun newCameraUri(): Uri {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    private fun copyDownscaled(uri: Uri): String? = try {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) null else {
            val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else bmp
            val dir = File(getApplication<Application>().filesDir, "chat_images").apply { mkdirs() }
            val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        }
    } catch (e: Exception) {
        null
    }

    // ---- voice -----------------------------------------------------------------------------

    var liveText by mutableStateOf("")
        private set
    private var streamHandle = 0L
    private var confirmedText = StringBuilder()
    private var chunkQueue: Channel<ByteArray>? = null
    private var liveJob: Job? = null
    private val pendingChunk = ByteArrayOutputStream()

    /**
     * Starts the mic. If the voice model supports streaming, words appear live while you speak
     * ([liveText]); otherwise the whole recording is transcribed when you stop.
     */
    fun startRecording() {
        if (busy || recording || transcribing) return
        if (modelStore.activeSpec(ModelRole.SPEECH) == null) {
            banner = "Install a voice model first (Models → Whisper Base)."
            return
        }
        transcribing = true // spinner while the voice model loads
        viewModelScope.launch {
            try {
                host.ensureLoaded(ModelRole.SPEECH)
                confirmedText = StringBuilder()
                liveText = ""
                pendingChunk.reset()
                val handle = try { host.speech.streamStart() } catch (e: Exception) { 0L }
                streamHandle = handle
                val queue = Channel<ByteArray>(Channel.UNLIMITED)
                chunkQueue = queue
                liveJob = if (handle != 0L) {
                    launch {
                        for (chunk in queue) {
                            try {
                                val t = host.speech.streamProcess(handle, chunk)
                                confirmedText.append(t.confirmed)
                                liveText = (confirmedText.toString() + t.pending).trim()
                            } catch (e: Exception) {
                                // Keep recording; the full recording is transcribed on stop if streaming fails.
                            }
                        }
                    }
                } else null
                recording = recorder.start(viewModelScope) { chunk ->
                    if (handle != 0L) {
                        synchronized(pendingChunk) {
                            pendingChunk.write(chunk)
                            if (pendingChunk.size() >= STREAM_CHUNK_BYTES) {
                                queue.trySend(pendingChunk.toByteArray())
                                pendingChunk.reset()
                            }
                        }
                    }
                }
                if (!recording) {
                    banner = "Could not start the microphone."
                    finishStream()
                }
            } catch (e: Exception) {
                banner = e.message ?: "Could not start voice input"
            } finally {
                transcribing = false
            }
        }
    }

    fun stopRecordingAndSend() {
        if (!recording) return
        recording = false
        transcribing = true
        viewModelScope.launch {
            try {
                val pcm = recorder.stop()
                synchronized(pendingChunk) {
                    if (pendingChunk.size() > 0) {
                        chunkQueue?.trySend(pendingChunk.toByteArray())
                        pendingChunk.reset()
                    }
                }
                var text = finishStream()
                if (pcm.size < PcmRecorder.SAMPLE_RATE / 2) {
                    banner = "Recording too short."
                    return@launch
                }
                if (text.isBlank()) text = host.speech.transcribe(pcm) // fallback: whole recording at once
                if (text.isBlank()) banner = "Didn't catch that." else send(text)
            } catch (e: Exception) {
                banner = e.message ?: "Transcription failed"
            } finally {
                transcribing = false
                liveText = ""
            }
        }
    }

    /** Ends the live stream (if any) and returns the full recognised text. */
    private suspend fun finishStream(): String {
        chunkQueue?.close()
        chunkQueue = null
        liveJob?.join()
        liveJob = null
        val handle = streamHandle
        streamHandle = 0L
        if (handle == 0L) return ""
        val rest = try { host.speech.streamStop(handle) } catch (e: Exception) { "" }
        return (confirmedText.toString() + rest).trim()
    }

    // ---- confirmations ---------------------------------------------------------------------

    private suspend fun askConfirmation(tool: Tool, args: JSONObject): Boolean {
        val pending = PendingConfirm(tool.name, args.toString(), CompletableDeferred())
        pendingConfirm = pending
        return try {
            pending.answer.await()
        } finally {
            pendingConfirm = null
        }
    }

    fun resolveConfirmation(allow: Boolean) {
        pendingConfirm?.answer?.complete(allow)
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun toolMessage(name: String, args: String, result: String) =
        UiMessage(nextId++, "tool", "⚙ $name($args)\n→ $result", kind = Kind.TOOL)

    private fun setText(id: Long, text: String) = updateMessage(id) { it.copy(text = text) }

    private fun updateMessage(id: Long, f: (UiMessage) -> UiMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = f(messages[i])
    }

    private fun insertBefore(id: Long, msg: UiMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages.add(i, msg) else messages.add(msg)
    }

    override fun onCleared() {
        speaker.shutdown()
        super.onCleared()
    }

    private companion object {
        const val MAX_IMAGE_SIDE = 1024
        const val STREAM_CHUNK_BYTES = 16_000 // 0.5 s of 16 kHz PCM16
    }
}
