package com.mediahub.feature.player.gesture

/** 双击分区判定结果（缺陷 34）。 */
enum class DoubleTapZone {
    TOGGLE_OVERLAY_LEFT,
    SEEK_BACKWARD,
    PLAY_PAUSE,
    SEEK_FORWARD,
    TOGGLE_OVERLAY_RIGHT,
}

/**
 * 播放器手势分区规则（缺陷 34 / 41 / 33）。
 *
 * 双击：
 * - 关闭"双击边缘显隐"时，保持既有 40% / 20% / 40% 三区，行为与旧版本完全一致；
 * - 开启后只把最外侧各 10% 让给"显隐控制栏"，中间 10%-90% 仍是原来的快退/播放/快进，
 *   因此不会破坏原有的双击 seek。
 *
 * 竖滑：
 * - 从底部系统手势区（含安全边距）起始的竖向手势必须让给系统，
 *   否则"上滑返回桌面"会被播放器误判为亮度/音量调节（缺陷 41）。
 *
 * 缩放：
 * - 手动缩放范围固定 1.0x..3.0x，1.0x 即原始画面。
 */
object PlayerGestureZonePolicy {

    /** 开启边缘显隐后，两侧显隐区宽度占屏宽的比例。 */
    const val OVERLAY_EDGE_FRACTION = 0.10f

    /** 原双击快退/快进分界：左 40% / 中 20% / 右 40%。 */
    const val ORIGINAL_SIDE_FRACTION = 0.40f
    const val ORIGINAL_CENTER_END_FRACTION = 0.60f

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 3f

    fun doubleTapZone(xFraction: Float, sidesToggleOverlayEnabled: Boolean): DoubleTapZone {
        val x = xFraction.coerceIn(0f, 1f)
        if (sidesToggleOverlayEnabled) {
            if (x < OVERLAY_EDGE_FRACTION) return DoubleTapZone.TOGGLE_OVERLAY_LEFT
            if (x < ORIGINAL_SIDE_FRACTION) return DoubleTapZone.SEEK_BACKWARD
            if (x <= ORIGINAL_CENTER_END_FRACTION) return DoubleTapZone.PLAY_PAUSE
            if (x <= 1f - OVERLAY_EDGE_FRACTION) return DoubleTapZone.SEEK_FORWARD
            return DoubleTapZone.TOGGLE_OVERLAY_RIGHT
        }
        return when {
            x < ORIGINAL_SIDE_FRACTION -> DoubleTapZone.SEEK_BACKWARD
            x <= ORIGINAL_CENTER_END_FRACTION -> DoubleTapZone.PLAY_PAUSE
            else -> DoubleTapZone.SEEK_FORWARD
        }
    }

    /**
     * 手势起点是否落在系统手势保留区（底部导航条/上滑返回区域）。
     * [bottomInsetPx] 为 `WindowInsets.systemGestures.bottom` 实测值；
     * 该值为 0（三键导航或未上报）时不保留，避免误伤普通竖滑。
     */
    fun isReservedSystemSwipe(
        startY: Float,
        heightPx: Float,
        bottomInsetPx: Float,
        extraMarginPx: Float = 0f,
    ): Boolean {
        if (bottomInsetPx <= 0f || heightPx <= 0f) return false
        val boundary = heightPx - bottomInsetPx - extraMarginPx.coerceAtLeast(0f)
        return startY >= boundary
    }

    fun clampZoom(rawScale: Float): Float = rawScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
}
