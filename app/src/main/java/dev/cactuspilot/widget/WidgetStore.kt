package dev.cactuspilot.widget

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

data class WidgetSnapshot(val prompt: String, val answer: String, val updatedAt: Long)

/** Persisted widget state (prompt, last answer). */
object WidgetStore {
    const val DEFAULT_PROMPT = "Give me a one-sentence motivational quote."
    private const val PREFS = "ask_widget"

    fun read(ctx: Context): WidgetSnapshot {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WidgetSnapshot(
            prompt = p.getString("prompt", DEFAULT_PROMPT) ?: DEFAULT_PROMPT,
            answer = p.getString("answer", "") ?: "",
            updatedAt = p.getLong("updatedAt", 0L),
        )
    }

    fun savePrompt(ctx: Context, prompt: String, refreshMinutes: Int?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("prompt", prompt)
            .putInt("refreshMinutes", refreshMinutes ?: 0)
            .apply()
    }

    fun saveAnswer(ctx: Context, answer: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("answer", answer)
            .putLong("updatedAt", System.currentTimeMillis())
            .apply()
    }
}

/** WorkManager scheduling for widget refreshes. */
object WidgetScheduler {
    private const val PERIODIC = "ask_widget_periodic"
    private const val ONCE = "ask_widget_once"

    fun schedule(ctx: Context, minutes: Int) {
        val req = PeriodicWorkRequestBuilder<WidgetWorker>(minutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
    }

    fun refreshNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<WidgetWorker>().build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }
}
