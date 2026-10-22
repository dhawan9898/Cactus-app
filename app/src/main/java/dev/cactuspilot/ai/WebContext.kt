package dev.cactuspilot.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional "fresher facts" helper: looks the question up on Wikipedia and hands the intro paragraphs to the
 * model as context. The model's own knowledge stops at its training date, so this grounds answers about
 * people, places, products and events. Only the question text is sent, to wikipedia.org, and only when the
 * Web toggle is on.
 */
object WebContext {
    data class Hit(val title: String, val extract: String)

    private val LEADING_QUESTION = Regex(
        "^(what|who|when|where|why|how|which)\\s+(is|are|was|were|does|do|did|has|have|will)?\\s*(the|a|an)?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val FILLER = Regex("^(tell me about|explain|describe|give me|please)\\s+", RegexOption.IGNORE_CASE)

    /** Turns a chat question into a search phrase. */
    fun toQuery(question: String): String {
        var q = question.trim().replace(Regex("[?!.]+$"), "")
        q = FILLER.replace(q, "")
        q = LEADING_QUESTION.replace(q, "")
        return q.take(150).ifBlank { question.take(150) }
    }

    suspend fun search(question: String): List<Hit> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(toQuery(question), "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search" +
            "&gsrsearch=$q&gsrlimit=3&prop=extracts&exintro=1&explaintext=1&exchars=700&redirects=1"
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "CactusPilot/1.0 (Android; offline-first assistant)")
        c.connectTimeout = 6_000
        c.readTimeout = 8_000
        try {
            if (c.responseCode != 200) return@withContext emptyList()
            parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    fun parse(json: String): List<Hit> {
        val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        return pages.keys().asSequence()
            .map { pages.getJSONObject(it) }
            .sortedBy { it.optInt("index", 99) }
            .map { Hit(it.optString("title"), it.optString("extract").trim()) }
            .filter { it.extract.isNotEmpty() }
            .toList()
    }

    fun format(hits: List<Hit>, today: String): String =
        hits.mapIndexed { i, h -> "[${i + 1}] ${h.title}: ${h.extract}" }
            .joinToString("\n\n", prefix = "Wikipedia excerpts retrieved on $today:\n")
}
