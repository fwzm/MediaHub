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
        config = VisualEffectConfig(
            speed = 0.9f,
            iridescence = 0.5f,
            bloom = 1.15f,
            noiseScale = 1.4f,
        ),
    )

    /** Light capsule, soft green/peach pastel wash — the reference video's MATCHA card. */
    val MatchaLight = FlowGlowPreset(
        name = "MATCHA.LIGHT",
        palette = VisualPalette(
            background = 0xFFF6F4EE.toInt(),
            primary = 0xFFCFE0AC.toInt(),
            secondary = 0xFFF4EDDC.toInt(),
            accent = 0xFFE8A47E.toInt(),
        ),
        config = VisualEffectConfig(
            speed = 0.8f,
            iridescence = 0f,
            bloom = 0.22f,
            opacity = 0.9f,
            noiseScale = 1.4f,
        ),
    )

    /** Warm sand/cream wash — the reference video's SAND card. */
    val SandGlow = FlowGlowPreset(
        name = "SAND.SURF*",
        palette = VisualPalette(
            background = 0xFFFBF8F3.toInt(),
            primary = 0xFFE3BD92.toInt(),
            secondary = 0xFFF7EFE1.toInt(),
            accent = 0xFFC8925E.toInt(),
        ),
        config = VisualEffectConfig(
            speed = 0.7f,
            iridescence = 0f,
            bloom = 0.18f,
            opacity = 0.9f,
            noiseScale = 1.4f,
        ),
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
        config = VisualEffectConfig(
            speed = 0.9f,
            iridescence = 0.35f,
            bloom = 1.0f,
            noiseScale = 1.4f,
        ),
    )

    val All = listOf(AuroraDark, MatchaLight, SandGlow, NebulaCinema)
}
