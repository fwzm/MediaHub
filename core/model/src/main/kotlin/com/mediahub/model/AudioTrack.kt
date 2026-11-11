package com.mediahub.model

/**
 * 音轨（供播放器选择器使用，字段与 Media3 轨道信息解耦）。
 *
 * [index] 是同类型 UI 行序号（0..N-1）。Media3 同组多轨展开后，行号不等于组号；
 * 播放期组/轨地址由 player:engine 的快照行映射提供，不扩散到领域模型。
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
