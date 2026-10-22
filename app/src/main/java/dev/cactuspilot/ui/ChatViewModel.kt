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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cactuspilot.ai.AgentLoop
import dev.cactuspilot.ai.ChatTurn
import dev.cactuspilot.ai.ModelRole
import dev.cactuspilot.ai.Tool
import dev.cactuspilot.pilotApp
import dev.cactuspilot.voice.PcmRecorder
import dev.cactuspilot.voice.Speaker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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
    private val store = app.pilotApp.modelStore
    private val agent = AgentLoop(host, app)
    private val recorder = PcmRecorder()
    private val speaker = Speaker(app)
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L

    val messages = mutableStateListOf<UiMessage>()
    var busy by mutableStateOf(false)
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
    var speakReplies by mutableStateOf(false)

    val nativeAvailable get() = host.nativeAvailable
    val activeChat get() = store.activeSpec(ModelRole.CHAT)

    fun dismissBanner() { banner = null }

    // ---- text / image turn ----------------------------------------------------------------

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || busy) return
        val image = pendingImage
        pendingImage = null
        val spec = store.activeSpec(ModelRole.CHAT)
        // Only hand images to models that can see; otherwise drop with a note.
        val usableImage = if (spec?.vision == true) image else null
        if (image != null && usableImage == null) banner = "The active model cannot read images; sent as text only."

        messages += UiMessage(nextId++, "user", prompt, usableImage)
        val replyId = nextId++
        messages += UiMessage(replyId, "assistant", "")
        busy = true

        val history = messages.filter { it.kind == Kind.TEXT && it.id != replyId && it.text.isNotEmpty() }
            .map { ChatTurn(it.role, it.text, it.imagePath) }

        viewModelScope.launch {
            val sb = StringBuilder()
            try {
                val result = agent.run(
                    history = history,
                    useTools = true,
                    confirm = ::askConfirmation,
                    onToken = { t -> main.post { sb.append(t); setText(replyId, sb.toString()) } },
                    onClear = { main.post { sb.clear(); setText(replyId, "") } },
                    onToolEvent = { e -> main.post { insertBefore(replyId, toolMessage(e.name, e.args, e.result)) } },
                )
                val stats = "%.1f tok/s · first token %.0f ms".format(result.decodeTps, result.ttftMs)
                updateMessage(replyId) { it.copy(text = result.text.trim().ifEmpty { "(no response)" }, stats = stats) }
                if (speakReplies) speaker.speak(result.text)
            } catch (e: Exception) {
                updateMessage(replyId) { it.copy(text = e.message ?: "Something went wrong", kind = Kind.ERROR) }
            } finally {
                busy = false
            }
        }
    }

    fun stopGeneration() {
        host.chat.stop()
        speaker.stop()
    }

    fun clearChat() {
        if (busy) return
        messages.clear()
    }

    // ---- image attach ----------------------------------------------------------------------

    fun attachImage(uri: Uri) {
        viewModelScope.launch {
            pendingImage = withContext(Dispatchers.IO) { copyDownscaled(uri) }
            if (pendingImage == null) banner = "Could not read that image."
            else if (store.activeSpec(ModelRole.CHAT)?.vision != true) {
                banner = "Active model is text-only. Switch to a vision model in Models."
            }
        }
    }

    fun removeImage() { pendingImage = null }

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
            val file = File(getApplication<Application>().cacheDir, "img_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        }
    } catch (e: Exception) {
        null
    }

    // ---- voice -----------------------------------------------------------------------------

    fun startRecording() {
        if (busy || recording || transcribing) return
        if (store.activeSpec(ModelRole.SPEECH) == null) {
            banner = "Install a voice model first (Models → Whisper Tiny)."
            return
        }
        recording = recorder.start(viewModelScope)
        if (!recording) banner = "Could not start the microphone."
    }

    fun stopRecordingAndSend() {
        if (!recording) return
        recording = false
        transcribing = true
        viewModelScope.launch {
            try {
                val pcm = recorder.stop()
                if (pcm.size < PcmRecorder.SAMPLE_RATE / 2) {
                    banner = "Recording too short."
                    return@launch
                }
                host.ensureLoaded(ModelRole.SPEECH)
                val text = host.speech.transcribe(pcm)
                if (text.isBlank()) banner = "Didn't catch that." else send(text)
            } catch (e: Exception) {
                banner = e.message ?: "Transcription failed"
            } finally {
                transcribing = false
            }
        }
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
    }
}
