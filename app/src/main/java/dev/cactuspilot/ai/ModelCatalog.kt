package dev.cactuspilot.ai

enum class ModelRole { CHAT, SPEECH }

data class ModelSpec(
    val id: String,
    val title: String,
    val role: ModelRole,
    val repo: String,
    val file: String,
    val sizeMb: Int,
    val vision: Boolean = false,
    val note: String = "",
) {
    val url: String get() = "https://huggingface.co/Cactus-Compute/$repo/resolve/main/$file"
}

/**
 * Data-driven catalog. If a bundle fails to load after an engine upgrade, check the repo tree on
 * Hugging Face and adjust `file` here; no other code needs to change.
 */
object ModelCatalog {
    val all: List<ModelSpec> = listOf(
        ModelSpec(
            "lfm25-vl-450m", "LFM2.5-VL 450M (fast)", ModelRole.CHAT,
            "LFM2.5-VL-450M-cq", "L1V4.zip", 122, vision = true,
            note = "Chat + image understanding. Best default for most phones.",
        ),
        ModelSpec(
            "lfm25-vl-450m-hq", "LFM2.5-VL 450M (better quality)", ModelRole.CHAT,
            "LFM2.5-VL-450M-cq", "L3V4.zip", 192, vision = true,
            note = "Same model, higher-precision bundle.",
        ),
        ModelSpec(
            "qwen3-0.6b", "Qwen3 0.6B", ModelRole.CHAT,
            "Qwen3-0.6B-cq", "L2.zip", 268,
            note = "Text only; good at following tool instructions.",
        ),
        ModelSpec(
            "gemma4-e2b", "Gemma 4 E2B (large)", ModelRole.CHAT,
            "gemma-4-e2b-it-cq", "A4V4L1.zip", 1415, vision = true,
            note = "Highest quality. Recommended for phones with 8 GB+ RAM.",
        ),
        ModelSpec(
            "whisper-tiny", "Whisper Tiny (voice input)", ModelRole.SPEECH,
            "whisper-tiny-cq", "CQ4.zip", 22,
            note = "Fast speech-to-text, English-leaning.",
        ),
        ModelSpec(
            "parakeet-v3", "Parakeet TDT 0.6B v3 (voice input)", ModelRole.SPEECH,
            "parakeet-tdt-0.6b-v3-cq", "CQ4.zip", 347,
            note = "More accurate and multilingual.",
        ),
    )

    fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }
    fun forRole(role: ModelRole): List<ModelSpec> = all.filter { it.role == role }
    fun defaultFor(role: ModelRole): ModelSpec = forRole(role).first()
}
