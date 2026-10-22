package dev.cactuspilot.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.cactuspilot.ai.AgentLoop
import dev.cactuspilot.ai.EngineJson
import dev.cactuspilot.ai.GenOptions
import dev.cactuspilot.ai.ModelRole
import dev.cactuspilot.pilotApp
import org.json.JSONArray

/** Runs the widget prompt once through the chat model and refreshes the widget. */
class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val snap = WidgetStore.read(ctx)
        val answer = try {
            val host = ctx.pilotApp.engineHost
            host.ensureLoaded(ModelRole.CHAT)
            val messages = JSONArray()
                .put(EngineJson.message("system", AgentLoop.systemPrompt(withTools = false)))
                .put(EngineJson.message("user", snap.prompt))
            host.chat.complete(messages, null, GenOptions(maxTokens = 160, temperature = 0.3)).text.trim()
        } catch (e: Exception) {
            "⚠ ${e.message ?: "Could not generate an answer"}"
        }
        WidgetStore.saveAnswer(ctx, answer)
        AskWidget().updateAll(ctx)
        return Result.success()
    }
}
