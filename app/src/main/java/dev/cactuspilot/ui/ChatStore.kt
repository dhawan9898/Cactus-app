package dev.cactuspilot.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists the conversation as a small JSON file so it survives app restarts and updates. */
class ChatStore(ctx: Context) {
    private val file = File(ctx.filesDir, "chat_history.json")

    fun load(): List<UiMessage> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UiMessage(
                    id = o.getLong("id"),
                    role = o.getString("role"),
                    text = o.getString("text"),
                    imagePath = o.optString("image").ifEmpty { null }?.takeIf { File(it).exists() },
                    kind = runCatching { Kind.valueOf(o.optString("kind", "TEXT")) }.getOrDefault(Kind.TEXT),
                    stats = o.optString("stats").ifEmpty { null },
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(messages: List<UiMessage>) {
        try {
            val arr = JSONArray()
            messages.filter { it.text.isNotEmpty() }.takeLast(MAX_MESSAGES).forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id).put("role", m.role).put("text", m.text)
                        .put("image", m.imagePath ?: "").put("kind", m.kind.name).put("stats", m.stats ?: ""),
                )
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {
            // History is best-effort; never crash the chat because of it.
        }
    }

    private companion object {
        const val MAX_MESSAGES = 200
    }
}
