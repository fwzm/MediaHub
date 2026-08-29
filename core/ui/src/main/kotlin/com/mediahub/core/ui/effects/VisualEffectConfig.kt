package com.mediahub.core.ui.effects

/**
 * Tunable parameters of the flow-glow shader field. Purely visual — no player, provider, or
 * playback state leaks into this type, which is what keeps the engine portable across
 * Emby/Jellyfin/WebDAV/local sources.
 */
data class VisualEffectConfig(
    /** Global time multiplier for field motion. */
    val speed: Float = 1f,
    /** Domain-warp strength (liquid distortion amount). */
    val warp: Float = 1f,
    /** Rainbow fringing at field edges; 0 disables the iridescent band. */
    val iridescence: Float = 0.55f,
    /** White-hot core highlight amount. */
    val bloom: Float = 0.85f,
    /** Master alpha of the shader layer over the capsule background. */
    val opacity: Float = 1f,
    /** Base spatial frequency of the noise field. */
    val noiseScale: Float = 1.7f,
    /** Multiplier applied to spectrum targets before smoothing. */
    val audioGain: Float = 1f,
    /** Shader refresh rate. The UI itself keeps composing at display rate. */
    val fps: Int = 30,
) {
    init {
        require(fps in 1..120) { "fps must be in 1..120, was $fps" }
    }

    companion object {
        val Default = VisualEffectConfig()
    }
}

/** A named palette + config bundle, e.g. one look of the capsule. */
data class FlowGlowPreset(
    val name: String,
    val palette: VisualPalette,
    val config: VisualEffectConfig,
)

/**
 * Built-in looks calibrated against the reference footage (dark card with white-hot core +
 * rainbow fringes, and the two pastel light cards).
 */
object FlowGlowPresets {

    /** Dark capsule, white-hot core + rainbow fringes — the reference video's dark card. */
    val AuroraDark = FlowGlowPreset(
        name = "AURORA.DARK*",
        palette = VisualPalette(
            background = 0xFF15151A.toInt(),
            primary = 0xFFF4EFE6.toInt(),
            secondary = 0xFF6B4A2F.toInt(),
            accent = 0xFFFF9D45.toInt(),
        ),
        config = VisualEffectConfig(iridescence = 0.9f, bloom = 1.1f, speed = 1f),
    )

    /** Light capsule, soft green/peach pastel wash — the reference video's MATCHA card. */
    val MatchaLight = FlowGlowPreset(
        name = "MATCHA.LIGHT",
        palette = VisualPalette(
            background = 0xFFF7F5F0.toInt(),
            primary = 0xFFDCE8C4.toInt(),
            secondary = 0xFFF2E9D8.toInt(),
            accent = 0xFFE8A87C.toInt(),
        ),
        config = VisualEffectConfig(iridescence = 0.12f, bloom = 0.15f, opacity = 0.85f, speed = 0.8f),
    )

    /** Warm sand/cream wash — the reference video's SAND card. */
    val SandGlow = FlowGlowPreset(
        name = "SAND.SURF*",
        palette = VisualPalette(
            background = 0xFFFBF8F4.toInt(),
            primary = 0xFFEACBA4.toInt(),
            secondary = 0xFFF5EADC.toInt(),
            accent = 0xFFC89A6A.toInt(),
        ),
        config = VisualEffectConfig(iridescence = 0f, bloom = 0.2f, opacity = 0.9f, speed = 0.7f),
    )

    /** Deep-space blue/violet; the default candidate for cinema playback chrome. */
    val NebulaCinema = FlowGlowPreset(
        name = "NEBULA",
        palette = VisualPalette(
            background = 0xFF0C0F1E.toInt(),
            primary = 0xFF9FB8FF.toInt(),
            secondary = 0xFF2A2554.toInt(),
            accent = 0xFFC86BFF.toInt(),
        ),
        config = VisualEffectConfig(iridescence = 0.5f, bloom = 0.9f, speed = 0.9f),
    )

    val All = listOf(AuroraDark, MatchaLight, SandGlow, NebulaCinema)
}
