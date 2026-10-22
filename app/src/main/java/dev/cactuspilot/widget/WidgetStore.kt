package dev.cactuspilot.widget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

data class WidgetSnapshot(
    val prompt: String,
    val answer: String,
    val updatedAt: Long,
    /** 0 = manual refresh only. */
    val refreshMinutes: Int,
)

/** Persisted per-widget state (prompt, last answer, refresh interval), keyed by appWidgetId. */
object WidgetStore {
    const val DEFAULT_PROMPT = "Give me a one-sentence motivational quote."
    private const val PREFS = "ask_widget"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(ctx: Context, id: Int): WidgetSnapshot {
        val p = prefs(ctx)
        return WidgetSnapshot(
            prompt = p.getString("p_$id", DEFAULT_PROMPT) ?: DEFAULT_PROMPT,
            answer = p.getString("a_$id", "") ?: "",
            updatedAt = p.getLong("t_$id", 0L),
            refreshMinutes = p.getInt("m_$id", 0),
        )
    }

    fun savePrompt(ctx: Context, id: Int, prompt: String, refreshMinutes: Int) {
        prefs(ctx).edit()
            .putString("p_$id", prompt)
            .putInt("m_$id", refreshMinutes)
            .putInt("last", id)
            .apply()
    }

    fun saveAnswer(ctx: Context, id: Int, answer: String) {
        prefs(ctx).edit().putString("a_$id", answer).putLong("t_$id", System.currentTimeMillis()).apply()
    }

    fun remove(ctx: Context, id: Int) {
        val p = prefs(ctx)
        val e = p.edit().remove("p_$id").remove("a_$id").remove("t_$id").remove("m_$id")
        if (p.getInt("last", 0) == id) e.remove("last")
        e.apply()
    }

    /** The widget most recently configured (used by the assistant's configure_widget tool), or 0. */
    fun lastConfigured(ctx: Context): Int = prefs(ctx).getInt("last", 0)

    val REFRESH_CHOICES = listOf(0 to "Manual", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 180 to "3 hours", 360 to "6 hours", 720 to "12 hours", 1440 to "Daily")
}

/** WorkManager scheduling for widget refreshes (one periodic job per widget). */
object WidgetScheduler {
    private fun periodicName(id: Int) = "ask_widget_periodic_$id"
    private fun onceName(id: Int) = "ask_widget_once_$id"

    fun schedule(ctx: Context, widgetId: Int, minutes: Int) {
        val req = PeriodicWorkRequestBuilder<WidgetWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setInputData(workDataOf(WidgetWorker.KEY_ID to widgetId))
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(periodicName(widgetId), ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context, widgetId: Int) {
        WorkManager.getInstance(ctx).cancelUniqueWork(periodicName(widgetId))
        WorkManager.getInstance(ctx).cancelUniqueWork(onceName(widgetId))
    }

    /** [widgetId] = 0 refreshes every widget. */
    fun refreshNow(ctx: Context, widgetId: Int = 0) {
        val req = OneTimeWorkRequestBuilder<WidgetWorker>()
            .setInputData(workDataOf(WidgetWorker.KEY_ID to widgetId))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(onceName(widgetId), ExistingWorkPolicy.REPLACE, req)
    }

    /** Applies a saved configuration: schedules (or cancels) periodic refresh and refreshes once now. */
    fun apply(ctx: Context, widgetId: Int, refreshMinutes: Int) {
        if (refreshMinutes > 0) schedule(ctx, widgetId, refreshMinutes) else cancel(ctx, widgetId)
        refreshNow(ctx, widgetId)
    }
}
