package dev.cactuspilot

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cactuspilot.ai.GenOptions

/** User preferences, observable from Compose and persisted in SharedPreferences. */
class AppSettings(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // "_v2" keys: defaults were retuned (calmer sampling, longer replies), so old saved values are ignored.
    var temperature by mutableStateOf(p.getFloat("temperature_v2", 0.3f))
        private set
    var maxTokens by mutableStateOf(p.getInt("max_tokens_v2", 1024))
        private set
    var contextTokens by mutableStateOf(p.getInt("context_tokens", 8192))
        private set
    var webSearch by mutableStateOf(p.getBoolean("web_search", false))
        private set
    var toolsEnabled by mutableStateOf(p.getBoolean("tools_enabled", true))
        private set
    var speakReplies by mutableStateOf(p.getBoolean("speak_replies", false))
        private set

    fun updateTemperature(v: Float) { temperature = v; p.edit().putFloat("temperature_v2", v).apply() }
    fun updateMaxTokens(v: Int) { maxTokens = v; p.edit().putInt("max_tokens_v2", v).apply() }
    fun updateContextTokens(v: Int) { contextTokens = v; p.edit().putInt("context_tokens", v).apply() }
    fun updateWebSearch(v: Boolean) { webSearch = v; p.edit().putBoolean("web_search", v).apply() }
    fun updateToolsEnabled(v: Boolean) { toolsEnabled = v; p.edit().putBoolean("tools_enabled", v).apply() }
    fun updateSpeakReplies(v: Boolean) { speakReplies = v; p.edit().putBoolean("speak_replies", v).apply() }

    fun genOptions() = GenOptions(maxTokens = maxTokens, temperature = temperature.toDouble())

    companion object {
        val CONTEXT_CHOICES = listOf(4096, 8192, 16384, 32768)
    }
}
