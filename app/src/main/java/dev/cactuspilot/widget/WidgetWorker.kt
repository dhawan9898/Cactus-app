package dev.cactuspilot.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.cactuspilot.ai.AgentLoop
import dev.cactuspilot.ai.EngineJson
import dev.cactuspilot.ai.GenOptions
import dev.cactuspilot.ai.ModelRole
import dev.cactuspilot.pilotApp
import org.json.JSONArray

/** Runs each widget's prompt once through the chat model and refreshes that widget (0 = all widgets). */
class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val only = inputData.getInt(KEY_ID, 0)
        val manager = GlanceAppWidgetManager(ctx)
        val targets = manager.getGlanceIds(AskWidget::class.java)
            .map { it to manager.getAppWidgetId(it) }
            .filter { only == 0 || it.second == only }

        for ((glanceId, widgetId) in targets) {
            val snap = WidgetStore.read(ctx, widgetId)
            val answer = try {
                val host = ctx.pilotApp.engineHost
                host.ensureLoaded(ModelRole.CHAT)
                val messages = JSONArray()
                    .put(EngineJson.message("system", AgentLoop.systemPrompt(withTools = false)))
                    .put(EngineJson.message("user", snap.prompt))
                host.chat.complete(messages, null, GenOptions(maxTokens = 200, temperature = 0.3)).text.trim()
            } catch (e: Exception) {
                "⚠ ${e.message ?: "Could not generate an answer"}"
            }
            WidgetStore.saveAnswer(ctx, widgetId, answer)
            AskWidget().update(ctx, glanceId)
        }
        return Result.success()
    }

    companion object {
        const val KEY_ID = "widget_id"
    }
}
