package com.mediahub.player.engine

/**
 * AFR（自动帧率匹配）决策（M2 / ADR-044）。
 *
 * @param targetRateHz 目标刷新率；0f 表示**不切换**（保持系统默认）。
 * @param multiplier 目标率 / 源帧率 的整数倍率；不切换时为 0。
 * @param reason 可诊断原因（进日志；不含 URL/凭据）。
 */
data class FrameRateDecision(
    val targetRateHz: Float,
    val multiplier: Int,
    val reason: String,
) {
    val shouldSwitch: Boolean get() = targetRateHz > 0f
}

/**
 * AFR 纯逻辑：在"源帧率 + 设备可用刷新率 + API 级别"之间做判定。
 *
 * 本对象**不接触 Surface / WindowManager / Display**，因此可离线单测；
 * 真正的下发（`Surface.setFrameRate`）由调用方在具备 Surface 与帧率信息时执行，
 * 且必须"失败即忽略、绝不阻断播放"。
 *
 * 能力差异（实测代码与平台事实）：
 * - `Surface.setFrameRate(float, int, int)`：**API 30+**（Android 11）。minSdk 26 → 必须运行时判级。
 * - `WindowManager.LayoutParams.preferredDisplayModeId`：API 23+，但需要 Activity/Window 与
 *   可枚举的 Display.Mode 列表，且对多窗口/分屏行为不一致，本项目**未采用**（见 ADR-044）。
 * - 手机端多数屏幕只有 60/120Hz 且系统可能自行调节；TV 端模式更丰富。两者都必须先取
 *   `Display.supportedModes`/`getSupportedRefreshRates()` 再判定，拿不到就**不切换**。
 */
object FrameRateMatcher {

    /** 常见片源帧率（含 NTSC 分数帧率）。 */
    val COMMON_SOURCE_RATES: List<Float> = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)

    /** 相对容差：0.5%（覆盖 23.976 与 24.0 面板之间 0.1% 的名义差）。 */
    private const val TOLERANCE = 0.005f

    /** 允许的最大整数倍率（120Hz 对应 24fps 即 5 倍）。 */
    private const val MAX_MULTIPLIER = 6

    /** setFrameRate 所需的最低 API 级别。 */
    const val MIN_API_FOR_SURFACE_FRAME_RATE = 30

    /**
     * @param sourceFps 源视频帧率；null/<=0 表示**未知** → 不切换（不猜测）。
     * @param availableRatesHz 设备可用刷新率（来自 Display API）；空 = 无证据 → 不切换。
     * @param apiLevel Build.VERSION.SDK_INT。
     */
    fun decide(sourceFps: Float?, availableRatesHz: List<Float>, apiLevel: Int): FrameRateDecision {
        if (apiLevel < MIN_API_FOR_SURFACE_FRAME_RATE) {
            return FrameRateDecision(0f, 0, "API " + apiLevel + " < " + MIN_API_FOR_SURFACE_FRAME_RATE + "：Surface.setFrameRate 不可用")
        }
        if (sourceFps == null || sourceFps <= 0f) {
            return FrameRateDecision(0f, 0, "源帧率未知：不做 AFR")
        }
        val rates = availableRatesHz.filter { it > 0f }.distinct().sorted()
        if (rates.isEmpty()) {
            return FrameRateDecision(0f, 0, "设备未报告可用刷新率：不做 AFR")
        }
        var best: Pair<Float, Int>? = null
        var bestError = Float.MAX_VALUE
        for (rate in rates) {
            for (multiplier in 1..MAX_MULTIPLIER) {
                val target = sourceFps * multiplier
                val error = kotlin.math.abs(rate - target) / target
                if (error <= TOLERANCE && error < bestError) {
                    bestError = error
                    best = rate to multiplier
                }
            }
        }
        val chosen = best
            ?: return FrameRateDecision(0f, 0, "可用刷新率 " + rates + " 与源帧率 " + sourceFps + " 无整数倍匹配：不做 AFR")
        return FrameRateDecision(
            targetRateHz = chosen.first,
            multiplier = chosen.second,
            reason = "源 " + sourceFps + "fps → " + chosen.first + "Hz（" + chosen.second + " 倍）",
        )
    }
}
