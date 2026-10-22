package dev.cactuspilot

import android.app.Application
import android.content.Context
import dev.cactuspilot.ai.EngineHost
import dev.cactuspilot.ai.ModelStore

/** Process-wide singletons (simple manual DI; shared by the UI and the widget worker). */
class CactusPilotApp : Application() {
    val modelStore: ModelStore by lazy { ModelStore(this) }
    val engineHost: EngineHost by lazy { EngineHost(modelStore) }
}

val Context.pilotApp: CactusPilotApp get() = applicationContext as CactusPilotApp
