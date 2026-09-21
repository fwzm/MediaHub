package com.mediahub.model

/**
 * 音轨（供播放器选择器使用，字段与 Media3 轨道信息解耦）。
 *
 * [index] 是**同类型轨道内的序号**（0..N-1）：等于播放器列表位置，也等于当前 Tracks
 * 快照中仅按音频类型过滤后的组序号，可直接作为 TrackSelection 的 groupIndex（ADR-032 统一
 * 三套 index 语义；引擎侧按真实 TrackGroup 解析该序号，见 ADR-041）。该序号不是
 * `MappedTrackInfo.getTrackGroups(...)` 的参数语义——后者的参数是 renderer 索引。
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
