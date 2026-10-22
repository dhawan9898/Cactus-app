package dev.cactuspilot.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ChatTurn(val role: String, val text: String, val imagePath: String? = null)

data class ToolEvent(val name: String, val args: String, val result: String)

/**
 * One user turn: build prompt → complete → (optional) run requested tools → final answer.
 * Tool execution is gated by `confirm` for tools that declare `confirm = true`.
 */
class AgentLoop(private val host: EngineHost, private val appContext: Context) {

    class Request(
        val history: List<ChatTurn>,
        val useTools: Boolean,
        val options: GenOptions,
        val contextTokens: Int,
        val webContext: String? = null,
    )

    suspend fun run(
        req: Request,
        confirm: suspend (Tool, JSONObject) -> Boolean,
        onStatus: (String) -> Unit,
        onToken: (String) -> Unit,
        onClear: () -> Unit,
        onToolEvent: (ToolEvent) -> Unit,
    ): CompletionResult {
        if (!host.chat.isLoaded) onStatus("Loading model…")
        host.ensureLoaded(ModelRole.CHAT)
        onStatus(if (req.history.any { it.imagePath != null }) "Looking at the image…" else "Thinking…")

        val reserve = if (req.useTools) TOOLS_RESERVE_TOKENS else 0
        val budget = (req.contextTokens - req.options.maxTokens - reserve - SAFETY_TOKENS).coerceAtLeast(MIN_BUDGET)
        val messages = buildMessages(req.history, systemPrompt(req.useTools), budget, req.webContext)

        val first = host.chat.complete(messages, if (req.useTools) ToolRegistry.toolsJson() else null, req.options, onToken)
        if (first.functionCalls.isEmpty()) return first

        // The streamed text was a raw tool call; drop it from the UI.
        onClear()
        messages.put(EngineJson.message("assistant", renderCalls(first.functionCalls)))

        val summaries = mutableListOf<String>()
        for (call in first.functionCalls) {
            onStatus("Running ${call.name}…")
            val result = runTool(call, confirm)
            onToolEvent(ToolEvent(call.name, call.arguments.toString(), result))
            messages.put(EngineJson.message("tool", result))
            summaries += result
        }

        onStatus("Writing the answer…")
        val answer = host.chat.complete(messages, null, req.options, onToken)
        return if (answer.text.isNotBlank()) answer else answer.copy(text = summaries.joinToString("\n"))
    }

    private suspend fun runTool(call: FunctionCall, confirm: suspend (Tool, JSONObject) -> Boolean): String {
        val tool = ToolRegistry.find(call.name) ?: return "error: unknown tool ${call.name}"
        if (tool.confirm && !confirm(tool, call.arguments)) return "The user denied this action."
        return try {
            tool.execute(appContext, call.arguments)
        } catch (e: Exception) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        private const val TOOLS_RESERVE_TOKENS = 700
        private const val SAFETY_TOKENS = 300
        private const val MIN_BUDGET = 1024
        private const val IMAGE_TOKENS = 320

        fun systemPrompt(withTools: Boolean, now: LocalDateTime = LocalDateTime.now()): String {
            val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))
            val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
            val base = """
                You are CactusPilot, a friendly and knowledgeable AI assistant running privately on the user's phone.
                Today is $date and the local time is $time.

                How to answer:
                - Use the whole conversation. Resolve words like "it", "that" or "the second one" from earlier messages, and build on your previous answers instead of starting over.
                - Be accurate and direct. If a question is ambiguous, ask one short clarifying question. If you are unsure, or the topic may have changed after your training data ends, say so instead of guessing.
                - If excerpts from the web are provided, prefer them over your memory and mention the source name.
                - Write in Markdown: short paragraphs, **bold** for key terms, bullet or numbered lists for steps, and fenced code blocks with a language tag for code.
                - Match the length to the question: brief for simple ones, thorough with steps or examples for complex ones.
            """.trimIndent()
            return if (withTools) {
                base + "\n- When the user asks you to do something on the device (alarm, timer, call, open an app, ...), call the matching tool instead of describing how to do it."
            } else base
        }

        /** Rough token estimate (the engine's tokenizer is not exposed cheaply); errs on the high side. */
        fun estimateTokens(text: String): Int = (text.length / 3.2).toInt() + 4

        /**
         * Builds the engine prompt from the conversation, dropping the oldest turns once [budgetTokens] is reached.
         * - Only the most recent image stays attached (images are expensive; follow-up questions still work).
         * - [webContext], when present, is added to the latest user message only (not stored in history).
         * - The prompt always starts with the system message, then a user turn, and ends with the latest user turn.
         */
        fun buildMessages(
            history: List<ChatTurn>,
            systemPrompt: String,
            budgetTokens: Int,
            webContext: String? = null,
        ): JSONArray {
            val lastImage = history.indexOfLast { it.imagePath != null }
            val turns = history.mapIndexed { i, t ->
                if (t.imagePath != null && i != lastImage) t.copy(imagePath = null) else t
            }.toMutableList()

            if (webContext != null && turns.isNotEmpty() && turns.last().role == "user") {
                val last = turns.last()
                turns[turns.lastIndex] = last.copy(
                    text = "$webContext\n\nUse the excerpts above if they help; otherwise ignore them.\n\nQuestion: ${last.text}",
                )
            }

            fun cost(t: ChatTurn) = estimateTokens(t.text) + if (t.imagePath != null) IMAGE_TOKENS else 0
            var total = estimateTokens(systemPrompt) + turns.sumOf(::cost)
            while (turns.size > 1 && total > budgetTokens) total -= cost(turns.removeAt(0))
            while (turns.size > 1 && turns[0].role != "user") turns.removeAt(0)

            val arr = JSONArray().put(EngineJson.message("system", systemPrompt))
            for (t in turns) arr.put(EngineJson.message(t.role, t.text, t.imagePath))
            return arr
        }

        /** Renders calls in the engine's chat-template form: `<|tool_call_start|>f(a="x")<|tool_call_end|>`. */
        fun renderCalls(calls: List<FunctionCall>): String = calls.joinToString("") { c ->
            val args = c.arguments.keys().asSequence().joinToString(", ") { k ->
                val v = c.arguments.get(k)
                "$k=${if (v is String) JSONObject.quote(v) else v.toString()}"
            }
            "<|tool_call_start|>${c.name}($args)<|tool_call_end|>"
        }
    }
}
