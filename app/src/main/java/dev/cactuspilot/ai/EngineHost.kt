package dev.cactuspilot.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns the two engine handles (chat + speech) and loads whatever model is currently active. */
class EngineHost(private val store: ModelStore, private val contextTokens: () -> Int) {
    val chat = CactusEngine("chat")
    val speech = CactusEngine("speech")
    private val loadLock = Mutex()

    /** False when libcactus_engine.so is missing or cannot be loaded on this device (needs arm64). */
    val nativeAvailable: Boolean by lazy {
        try {
            Class.forName("com.cactus.CactusJNI")
            true
        } catch (t: Throwable) {
            false
        }
    }

    suspend fun ensureLoaded(role: ModelRole) = loadLock.withLock {
        if (!nativeAvailable) throw EngineException("Native engine unavailable on this device (arm64 required).")
        val engine = if (role == ModelRole.CHAT) chat else speech
        val dir = store.activeModelDir(role)
            ?: throw EngineException(
                if (role == ModelRole.CHAT) "No chat model installed. Open Models and download one."
                else "No voice model installed. Open Models and download one.",
            )
        engine.load(dir, contextTokens())
    }
}
