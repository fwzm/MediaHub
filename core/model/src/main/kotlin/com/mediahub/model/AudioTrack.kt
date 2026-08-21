package com.mediahub.model

/**
 * 音轨（供播放器选择器使用，字段与 Media3 轨道信息解耦）。
 *
 * [index] 是**同类型轨道内的序号**（0..N-1，音轨列表位置 == 引擎 per-renderer 组序号），
 * 可直接作为 TrackSelection 的 groupIndex（Phase 1B-2.4 统一三套 index 语义）。
 */
data class AudioTrack(
    val index: Int,
    val language: String? = null,
    val title: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null,
    /** 容器内标记为默认轨（Media3 selectionFlags FLAG_DEFAULT）。 */
    val isDefault: Boolean = false,
    /** 当前实际被选中。 */
    val isSelected: Boolean = false,
    /** 设备/选择器当前是否支持该轨（Tracks.Group.isTrackSupported）。 */
    val isSupported: Boolean = true,
    /** 诊断：MediaCodecUtil 解析到的解码器名（null=未找到，可能不支持）。 */
    val decoderName: String? = null,
)
