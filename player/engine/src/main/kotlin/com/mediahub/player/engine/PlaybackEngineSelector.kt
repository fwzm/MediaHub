package com.mediahub.player.engine

import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackSource

/**
 * 兼容性签名（U3-A）：container|videoCodec|audioCodec 小写归一。
 * 同签名共享兼容性结论——一旦某签名在 Media3 上失败并经 mpv 恢复，
 * 后续同签名直接走 mpv（"越用越准"）。
 */
data class CompatibilitySignature(
    val container: String?,
    val videoCodec: String?,
    val audioCodec: String?,
) {
    val key: String
        get() = listOf(container, videoCodec, audioCodec)
            .joinToString("|") { it?.lowercase()?.trim().orEmpty() }

    companion object {
        fun from(source: PlaybackSource): CompatibilitySignature =
            CompatibilitySignature(
                container = source.container?.lowercase()?.trim(),
                videoCodec = source.videoCodec?.lowercase()?.trim(),
                audioCodec = source.audioCodec?.lowercase()?.trim(),
            )
    }
}

/** 引擎选择结论。 */
data class EngineSelection(
    val kind: EngineKind,
    val reason: String,
)

/**
 * 播放内核选择器（U3-A，纯逻辑可单测）。
 *
 * 输入：播放源签名 + 用户内核偏好 + 历史失败指纹。
 * 输出：Media3（快速路径）或 mpv（兼容路径）。
 *
 * 规则（优先级从高到低）：
 * 1. 用户显式指定 MEDIA3 / MPV → 直接采用；
 * 2. AUTO：签名命中历史失败指纹（Media3 已证明失败）→ mpv；
 * 3. AUTO：音频编码属 MPV 优先集合（DTS 家族 / TrueHD——Android 平台
 *    Media3 普遍无声，FFmpeg 软解为唯一可靠路径）→ mpv；
 * 4. 其余 → Media3（起播快、省电；失败时由 SwitchablePlaybackEngine 自动降级）。
 *
 * 设备能力（DeviceCapabilities）参与决策留待真实样本校准后接入，避免
 * 在 MediaCodecList 标注不可靠的情况下误判。
 */
object PlaybackEngineSelector {

    /** Android Media3 普遍无法输出声音的音频编码（FFmpeg 软解兜底）。 */
    private val MPV_PREFERRED_AUDIO = setOf("dts", "dts-hd", "dts-hd ma", "dts-hd hra", "truehd", "mlp")

    fun select(
        source: PlaybackSource,
        mode: PlaybackEngineMode,
        mpvPreferredSignatures: Set<String>,
    ): EngineSelection {
        if (mode == PlaybackEngineMode.MEDIA3) {
            return EngineSelection(EngineKind.MEDIA3, "用户指定 Media3")
        }
        if (mode == PlaybackEngineMode.MPV) {
            return EngineSelection(EngineKind.MPV, "用户指定 mpv")
        }
        val signature = CompatibilitySignature.from(source)
        if (signature.key in mpvPreferredSignatures) {
            return EngineSelection(EngineKind.MPV, "历史指纹命中（${signature.key}）")
        }
        if (signature.audioCodec in MPV_PREFERRED_AUDIO) {
            return EngineSelection(EngineKind.MPV, "音频 ${signature.audioCodec} 优先 mpv（软解）")
        }
        return EngineSelection(EngineKind.MEDIA3, "AUTO 默认快速路径（${signature.key}）")
    }
}
