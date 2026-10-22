package dev.cactuspilot.ai

enum class ModelRole { CHAT, SPEECH }

data class ModelSpec(
    val id: String,
    val title: String,
    val role: ModelRole,
    val repo: String,
    /** Hugging Face git tag the engine release is compatible with (what `cactus download` resolves). */
    val revision: String,
    val file: String,
    val sizeMb: Int,
    val vision: Boolean = false,
    val note: String = "",
) {
    val url: String get() = "https://huggingface.co/Cactus-Compute/$repo/resolve/$revision/$file"
}

/**
 * Data-driven catalog of *runnable bundles* for the pinned engine (Cactus v2.2.2).
 *
 * The v2 engine only loads bundles that contain `components/manifest.json`. These are the `*-cq4.zip`
 * archives that `cactus download` fetches from `Cactus-Compute/<model>` at the newest HF tag that is
 * <= the engine version. When upgrading the engine, re-run that resolution (list tags, pick newest
 * <= engine version, pick the `cq4` zip) and update the rows below.
 */
object ModelCatalog {
    val all: List<ModelSpec> = listOf(
        ModelSpec(
            "lfm2-vl-450m", "LFM2-VL 450M (fast)", ModelRole.CHAT,
            "LFM2-VL-450M", "v2.0", "lfm2-vl-450m-cq4.zip", 383, vision = true,
            note = "Chat + image understanding. Best default for most phones.",
        ),
        ModelSpec(
            "gemma4-e2b", "Gemma 4 E2B (large)", ModelRole.CHAT,
            "gemma-4-E2B-it", "v2.0.1", "gemma-4-e2b-it-cq4.zip", 2721, vision = true,
            note = "Highest quality, big download. Recommended for phones with 8 GB+ RAM.",
        ),
        ModelSpec(
            "whisper-base", "Whisper Base (voice input)", ModelRole.SPEECH,
            "whisper-base", "v2.0", "whisper-base-cq4.zip", 113,
            note = "Fast speech-to-text.",
        ),
        ModelSpec(
            "parakeet-v3", "Parakeet TDT 0.6B v3 (voice input)", ModelRole.SPEECH,
            "parakeet-tdt-0.6b-v3", "v2.2.2", "parakeet-tdt-0.6b-v3-cq4.zip", 384,
            note = "More accurate and multilingual.",
        ),
    )

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }
    fun forRole(role: ModelRole): List<ModelSpec> = all.filter { it.role == role }
    fun defaultFor(role: ModelRole): ModelSpec = forRole(role).first()
}
