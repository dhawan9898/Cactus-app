package dev.cactuspilot

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cactuspilot.ai.GenOptions

/** User preferences, observable from Compose and persisted in SharedPreferences. */
class AppSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var temperature by mutableStateOf(p.getFloat("temperature", 0.7f))
        private set
    var maxTokens by mutableStateOf(p.getInt("max_tokens", 512))
        private set
    var toolsEnabled by mutableStateOf(p.getBoolean("tools_enabled", true))
        private set
    var speakReplies by mutableStateOf(p.getBoolean("speak_replies", false))
        private set

    fun updateTemperature(v: Float) { temperature = v; p.edit().putFloat("temperature", v).apply() }
    fun updateMaxTokens(v: Int) { maxTokens = v; p.edit().putInt("max_tokens", v).apply() }
    fun updateToolsEnabled(v: Boolean) { toolsEnabled = v; p.edit().putBoolean("tools_enabled", v).apply() }
    fun updateSpeakReplies(v: Boolean) { speakReplies = v; p.edit().putBoolean("speak_replies", v).apply() }

    fun genOptions() = GenOptions(maxTokens = maxTokens, temperature = temperature.toDouble())
}
