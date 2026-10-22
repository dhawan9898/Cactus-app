package dev.cactuspilot.ai

import com.cactus.CactusJNI
import com.cactus.CactusTokenCallback
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

data class FunctionCall(val name: String, val arguments: JSONObject)

data class CompletionResult(
    val text: String,
    val functionCalls: List<FunctionCall>,
    val ttftMs: Double,
    val decodeTps: Double,
    val totalMs: Double,
)

class EngineException(message: String) : Exception(message)

/** Generation settings; maps 1:1 onto the engine's options JSON. */
data class GenOptions(
    val maxTokens: Int = 512,
    val temperature: Double = 0.7,
    val topP: Double = 0.9,
    val topK: Int = 40,
    val toolRagTopK: Int = 3,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("max_tokens", maxTokens)
        .put("temperature", temperature)
        .put("top_p", topP)
        .put("top_k", topK)
        .put("tool_rag_top_k", toolRagTopK)
        .put("auto_handoff", false) // stay fully on-device
}

/**
 * Thin, thread-safe wrapper over one Cactus model handle.
 * All native calls run on a dedicated single thread, so calls on one handle are serialized.
 */
class CactusEngine(private val label: String) {
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "cactus-$label") }.asCoroutineDispatcher()

    @Volatile private var handle: Long = 0L
    @Volatile var loadedDir: String? = null
        private set

    val isLoaded: Boolean get() = handle != 0L

    suspend fun load(modelDir: String) = withContext(dispatcher) {
        if (handle != 0L && loadedDir == modelDir) return@withContext
        unloadNow()
        val h = CactusJNI.nativeInit(modelDir, null, false)
        if (h == 0L) throw EngineException("Could not load model: ${CactusJNI.nativeGetLastError()}")
        handle = h
        loadedDir = modelDir
    }

    suspend fun unload() = withContext(dispatcher) { unloadNow() }

    private fun unloadNow() {
        if (handle != 0L) CactusJNI.nativeDestroy(handle)
        handle = 0L
        loadedDir = null
    }

    /** Ask the engine to stop the running generation (safe to call from any thread). */
    fun stop() {
        val h = handle
        if (h != 0L) CactusJNI.nativeStop(h)
    }

    suspend fun complete(
        messages: JSONArray,
        tools: JSONArray?,
        options: GenOptions = GenOptions(),
        onToken: (String) -> Unit = {},
    ): CompletionResult = withContext(dispatcher) {
        val h = requireHandle()
        val buf = ByteArray(RESPONSE_BYTES)
        val cb = CactusTokenCallback { token, _ -> onToken(token) }
        val n = CactusJNI.nativeComplete(
            h, messages.toString(), buf, options.toJson().toString(), tools?.toString(), cb, null,
        )
        parseCompletion(buf, n)
    }

    /** [pcm16] is 16 kHz mono signed 16-bit little-endian PCM. */
    suspend fun transcribe(pcm16: ByteArray, language: String = "en"): String = withContext(dispatcher) {
        val h = requireHandle()
        val buf = ByteArray(RESPONSE_BYTES)
        val opts = JSONObject().put("language", language).toString()
        val n = CactusJNI.nativeTranscribe(h, null, "", buf, opts, null, pcm16)
        parseCompletion(buf, n).text.trim()
    }

    private fun requireHandle(): Long {
        val h = handle
        if (h == 0L) throw EngineException("No model loaded ($label)")
        return h
    }

    private fun parseCompletion(buf: ByteArray, written: Int): CompletionResult {
        if (written < 0) throw EngineException("Engine error: ${CactusJNI.nativeGetLastError()}")
        return EngineJson.parseCompletion(String(buf, 0, written.coerceAtMost(buf.size), Charsets.UTF_8))
    }

    private companion object {
        const val RESPONSE_BYTES = 128 * 1024
    }
}

/** Pure JSON helpers, kept separate from JNI so they can be unit-tested on the JVM. */
object EngineJson {
    fun parseCompletion(raw: String): CompletionResult {
        val json = JSONObject(raw.substringBefore('\u0000').trim())
        if (!json.optBoolean("success", true)) {
            throw EngineException(json.optString("error", "unknown engine error"))
        }
        val calls = mutableListOf<FunctionCall>()
        val arr = json.optJSONArray("function_calls")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                calls += FunctionCall(c.getString("name"), c.optJSONObject("arguments") ?: JSONObject())
            }
        }
        return CompletionResult(
            text = if (json.isNull("response")) "" else json.optString("response", ""),
            functionCalls = calls,
            ttftMs = json.optDouble("time_to_first_token_ms", 0.0),
            decodeTps = json.optDouble("decode_tps", 0.0),
            totalMs = json.optDouble("total_time_ms", 0.0),
        )
    }

    fun message(role: String, content: String, imagePath: String? = null): JSONObject {
        val m = JSONObject().put("role", role).put("content", content)
        if (imagePath != null) m.put("images", JSONArray().put(imagePath))
        return m
    }
}
