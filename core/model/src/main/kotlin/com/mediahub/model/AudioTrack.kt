package com.mediahub.model

/**
 * 音轨（供播放器选择器使用，字段与 Media3 轨道信息解耦）。
 *
 * [index] 是**列表行序号**（0..N-1），只表示 UI 列表位置，**不代表引擎的组号**。
 * 同组多轨展开后，行序号与 `groupIndex` / `trackIndex` 不是同一个值；行到引擎地址的映射
 * 由 player:engine 的 `TrackSelection` 与 feature/player 的行转换入口负责。
 *
 * 运行时选择地址（组号 + 组内轨号 + 快照令牌）不放在本模块，避免把播放期内部地址扩散到
 * 领域模型与各 Provider（见 ADR-032 勘误）。
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
