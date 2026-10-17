package com.mediahub.player.mpv

import com.mediahub.model.HdrType

/**
 * mpv 调优输入（M2，ADR-044）。
 *
 * 纪律：所有字段都必须是**已采集的证据**，不得凭参数名或直觉填充。
 * - [deviceSupportsSourceHdr] 为 null 表示"设备 HDR 能力未知"——此时**不做任何色调映射干预**，
 *   宁可保持既有直通行为，也不猜测。
 */
data class MpvTuningInput(
    val sourceHdr: HdrType = HdrType.NONE,
    /** true/false = 设备显示能力明确；null = 未知（不干预）。 */
    val deviceSupportsSourceHdr: Boolean? = null,
    /** 下混归一化：默认关闭，需用户显式开启（本轮无 UI 入口，机制与测试先行）。 */
    val normalizeDownmix: Boolean = false,
    /** 输出音量上限（百分比）；null = 不干预。 */
    val volumeMaxPercent: Int? = null,
)

/** 一个 mpv 选项键值。 */
data class MpvTuningOption(val name: String, val value: String)

/**
 * 设备对某个 HDR 格式的支持证据。
 *
 * 返回 true/false = 有明确设备证据；返回 null = **未知**（不下发任何色调映射选项）。
 * 实现必须是同步、无 I/O 的（只读设备能力缓存快照）。
 */
fun interface DeviceHdrEvidenceProvider {
    fun supports(hdr: HdrType): Boolean?
}

/**
 * mpv 播放参数配置（M2，ADR-044）——建立"可维护、可测试、有证据"的参数层。
 *
 * ## 证据来源（方法可复现）
 * 对打包产物 `dev.jdtech.mpv:libmpv:1.0.0` 的 AAR 内 `jni/arm64-v8a/libmpv.so`
 * 做字符串表探测（2026-10-14），命中计数：
 * `hwdec`=65（方法自检：该选项生产已在用）、`tone-mapping`=13、`tone-mapping-mode`=1、
 * `hdr-compute-peak`=2、`libplacebo`=12、`gpu-api`=1、`target-peak`=1、
 * `gamut-mapping-mode`=3、`audio-normalize-downmix`=1、`volume-max`=1、`sub-ass`=23。
 *
 * ## 明确**不可用**（因此禁止在本项目中使用）
 * - `audio-downmix`：该版本 libmpv 字符串表 **0 命中**；设置会被 mpv 忽略（调用只返回错误码，不生效）。
 *   因此"中置声道 +3~4.5dB"这类依赖该选项的调音**无法实现**，不得写成已支持。
 * - `af=loudnorm`（EBU R128 响度归一化）：`libavfilter.so` 中 `loudnorm` **0 命中**
 *   （`dynaudnorm`/`acompressor` 同为 0），滤镜未编入 → **BLOCKED_EVIDENCE**，不得实现也不得展示为可用。
 * - Dolby Vision Profile 7→8.1 转换（libdovi）：无证据表明编入；本层**不做任何 DV 声明**。
 */
object MpvTuningOptions {

    /** 色调映射曲线：libmpv 1.0.0 字符串表中 `bt.2390` 唯一命中。 */
    const val TONE_MAPPING_CURVE: String = "bt.2390"

    /** 经过二进制证据确认存在的选项名白名单——本层只允许输出这些选项。 */
    val EVIDENCED_OPTIONS: Set<String> = setOf(
        "tone-mapping",
        "tone-mapping-mode",
        "hdr-compute-peak",
        "target-peak",
        "gamut-mapping-mode",
        "audio-normalize-downmix",
        "volume-max",
    )

    /** 文档存在但打包版本缺失的选项（设置无效，禁止使用）。 */
    val ABSENT_OPTIONS: Set<String> = setOf("audio-downmix")

    /** 未编入的音频滤镜（禁止作为"响度归一化已支持"的依据）。 */
    val UNCOMPILED_FILTERS: Set<String> = setOf("loudnorm", "dynaudnorm", "acompressor")

    /**
     * 生成需要下发的 mpv 选项。
     *
     * 规则：
     * 1. HDR 片源 **且** 设备明确不支持该 HDR 格式 → 色调映射（bt.2390 + 动态峰值检测）；
     * 2. 设备能力未知（null）或明确支持 → 不下发任何色调映射选项，保持既有直通行为；
     * 3. 下混归一化仅在显式请求时下发；
     * 4. 音量上限钳制到 100..1000（100 = 不放大）。
     */
    fun build(input: MpvTuningInput): List<MpvTuningOption> {
        val options = mutableListOf<MpvTuningOption>()
        if (input.sourceHdr != HdrType.NONE && input.deviceSupportsSourceHdr == false) {
            options += MpvTuningOption("tone-mapping", TONE_MAPPING_CURVE)
            options += MpvTuningOption("hdr-compute-peak", "yes")
        }
        if (input.normalizeDownmix) {
            options += MpvTuningOption("audio-normalize-downmix", "yes")
        }
        input.volumeMaxPercent?.let { requested ->
            options += MpvTuningOption("volume-max", requested.coerceIn(100, 1000).toString())
        }
        return options
    }
}
