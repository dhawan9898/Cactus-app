package dev.cactuspilot.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ConversationMeta(val id: String, val title: String, val updatedAt: Long)

/**
 * Stores each conversation as its own JSON file plus a small index, under `filesDir/conversations/`.
 * Writes are atomic (temp file + rename) and best-effort: history must never crash the chat.
 */
class ConversationStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "conversations").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val legacyFile = File(ctx.filesDir, "chat_history.json")
    private val prefs = ctx.getSharedPreferences("conversations", Context.MODE_PRIVATE)

    var activeId: String?
        get() = prefs.getString("active", null)
        set(v) { prefs.edit().putString("active", v).apply() }

    /** Newest first. Imports the single-chat history of older builds on first run. */
    fun list(): List<ConversationMeta> {
        migrateLegacy()
        return readIndex().sortedByDescending { it.updatedAt }
    }

    fun create(): ConversationMeta {
        val meta = ConversationMeta(UUID.randomUUID().toString(), DEFAULT_TITLE, System.currentTimeMillis())
        writeIndex(readIndex() + meta)
        return meta
    }

    fun load(id: String): List<UiMessage> = try {
        val f = file(id)
        if (f.exists()) decode(f.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Saves [messages] and refreshes the index entry (title comes from the first user message). Returns the entry. */
    fun save(id: String, messages: List<UiMessage>): ConversationMeta? {
        try {
            atomicWrite(file(id), encode(messages))
            val index = readIndex().toMutableList()
            val i = index.indexOfFirst { it.id == id }
            if (i < 0) return null
            val old = index[i]
            val title = if (old.title == DEFAULT_TITLE) {
                messages.firstOrNull { it.role == "user" && it.kind == Kind.TEXT }?.let { titleFrom(it.text) } ?: old.title
            } else old.title
            val updated = old.copy(title = title, updatedAt = System.currentTimeMillis())
            index[i] = updated
            writeIndex(index)
            return updated
        } catch (e: Exception) {
            return null
        }
    }

    fun rename(id: String, title: String) {
        val clean = title.trim().take(60).ifEmpty { return }
        writeIndex(readIndex().map { if (it.id == id) it.copy(title = clean) else it })
    }

    fun delete(id: String) {
        file(id).delete()
        writeIndex(readIndex().filterNot { it.id == id })
        if (activeId == id) activeId = null
    }

    private fun file(id: String) = File(dir, "$id.json")

    private fun readIndex(): List<ConversationMeta> = try {
        if (!indexFile.exists()) emptyList() else {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ConversationMeta(o.getString("id"), o.getString("title"), o.getLong("updatedAt"))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeIndex(items: List<ConversationMeta>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("updatedAt", it.updatedAt)) }
        atomicWrite(indexFile, arr.toString())
    }

    private fun migrateLegacy() {
        if (!legacyFile.exists() || indexFile.exists()) return
        try {
            val meta = create()
            val old = decode(legacyFile.readText())
            if (old.isEmpty()) {
                delete(meta.id)
            } else {
                save(meta.id, old)
                activeId = meta.id
            }
            legacyFile.delete()
        } catch (_: Exception) {
        }
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        const val DEFAULT_TITLE = "New chat"
        private const val MAX_MESSAGES = 300

        /** A short title from the first line of the first user message. */
        fun titleFrom(text: String): String {
            val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (line.isEmpty()) return DEFAULT_TITLE
            return if (line.length <= 40) line else line.take(40).trimEnd() + "…"
        }

        fun encode(messages: List<UiMessage>): String {
            val arr = JSONArray()
            messages.filter { it.text.isNotEmpty() }.takeLast(MAX_MESSAGES).forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id).put("role", m.role).put("text", m.text)
                        .put("image", m.imagePath ?: "").put("kind", m.kind.name).put("stats", m.stats ?: ""),
                )
            }
            return arr.toString()
        }

        fun decode(json: String): List<UiMessage> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
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
    }
}
