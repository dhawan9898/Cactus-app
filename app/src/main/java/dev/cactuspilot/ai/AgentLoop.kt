package dev.cactuspilot.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ChatTurn(val role: String, val text: String, val imagePath: String? = null)

data class ToolEvent(val name: String, val args: String, val result: String)

/**
 * One user turn: complete → (optional) run requested tools → final answer.
 * Tool execution is gated by [confirm] for tools that declare `confirm = true`.
 */
class AgentLoop(private val host: EngineHost, private val appContext: Context) {

    suspend fun run(
        history: List<ChatTurn>,
        useTools: Boolean,
        options: GenOptions = GenOptions(),
        confirm: suspend (Tool, JSONObject) -> Boolean,
        onToken: (String) -> Unit,
        onClear: () -> Unit,
        onToolEvent: (ToolEvent) -> Unit,
    ): CompletionResult {
        host.ensureLoaded(ModelRole.CHAT)
        val messages = buildMessages(history)
        val first = host.chat.complete(messages, if (useTools) ToolRegistry.toolsJson() else null, options, onToken)
        if (first.functionCalls.isEmpty()) return first

        // The streamed text was a raw tool call; drop it from the UI.
        onClear()
        messages.put(EngineJson.message("assistant", renderCalls(first.functionCalls)))

        val summaries = mutableListOf<String>()
        for (call in first.functionCalls) {
            val result = runTool(call, confirm)
            onToolEvent(ToolEvent(call.name, call.arguments.toString(), result))
            messages.put(EngineJson.message("tool", result))
            summaries += result
        }

        val answer = host.chat.complete(messages, null, options, onToken)
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
        const val SYSTEM_PROMPT =
            "You are CactusPilot, a helpful assistant running privately on the user's phone. " +
                "Be concise. When the user asks you to do something on the device, call the matching " +
                "tool instead of describing how to do it."

        const val MAX_HISTORY_TURNS = 12

        fun buildMessages(history: List<ChatTurn>): JSONArray {
            val arr = JSONArray().put(EngineJson.message("system", SYSTEM_PROMPT))
            for (t in history.takeLast(MAX_HISTORY_TURNS)) {
                arr.put(EngineJson.message(t.role, t.text, t.imagePath))
            }
            return arr
        }

        /** Renders calls in the engine's chat-template form: `<|tool_call_start|>f(a="x")<|tool_call_end|>`. */
        fun renderCalls(calls: List<FunctionCall>): String = calls.joinToString("") { c ->
            val args = c.arguments.keys().asSequence().joinToString(", ") { k ->
                "$k=${JSONObject.valueToString(c.arguments.get(k))}"
            }
            "<|tool_call_start|>${c.name}($args)<|tool_call_end|>"
        }
    }
}
