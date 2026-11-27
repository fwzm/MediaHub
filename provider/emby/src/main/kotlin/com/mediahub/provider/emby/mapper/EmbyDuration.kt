package com.mediahub.provider.emby.mapper

/**
 * Emby `RunTimeTicks` → 毫秒的**兼容兜底**换算（fnOS 等第三方 Emby 兼容服经验值）。
 *
 * Emby 官方契约：RunTimeTicks 单位是 100 纳秒（1 秒 = 10_000_000 ticks），
 * 标准换算即 `ticks / 10_000`。但实测部分兼容服（如 fnOS）会把**毫秒**甚至**秒**
 * 塞进同一个字段，直接按标准换算会得到 <1 秒或数十倍的荒谬时长（用户条目 79）。
 *
 * 因此按数量级分支（不是 Emby 标准，只是经验兜底）：
 * - `ticks >= 10_000_000`（≥1 秒的标准 ticks）→ 标准换算 `ticks / 10_000`；
 * - `10_000 <= ticks < 10_000_000` → 视为服务端已返回**毫秒**（标准换算只有 <1 秒，视频不可能）；
 * - `0 < ticks < 10_000` → 视为服务端返回**秒**，乘 1000；
 * - null / `<= 0` → null；
 * - 结果必须落在 1ms..7 天，否则 null（宁可不显示也不显示荒谬值）。
 */
object EmbyDuration {

    /** Emby RunTimeTicks 单位：100 纳秒；1 毫秒 = 10_000 ticks。 */
    private const val TICKS_PER_MILLIS = 10_000L

    /** 1 秒的标准 ticks；达到该量级才按官方单位换算。 */
    private const val ONE_SECOND_TICKS = 10_000_000L

    /** 毫秒尺度下界：低于该值只可能是兼容服的秒尺度或垃圾值。 */
    private const val MILLIS_SCALE_FLOOR = 10_000L

    /** 合法时长下界（毫秒）。 */
    private const val MIN_VALID_MS = 1L

    /** 合法时长上界：7 天（毫秒）。 */
    private const val MAX_VALID_MS = 7L * 24 * 60 * 60 * 1000

    /** 服务端 RunTimeTicks → 毫秒；不可信值返回 null。 */
    fun fromRunTimeTicks(ticks: Long?): Long? {
        val raw = ticks ?: return null
        if (raw <= 0L) return null
        val ms = when {
            raw >= ONE_SECOND_TICKS -> raw / TICKS_PER_MILLIS
            raw >= MILLIS_SCALE_FLOOR -> raw
            else -> raw * 1_000L
        }
        return ms.takeIf { it in MIN_VALID_MS..MAX_VALID_MS }
    }
}
