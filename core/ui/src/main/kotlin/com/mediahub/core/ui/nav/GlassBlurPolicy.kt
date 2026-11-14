package com.mediahub.core.ui.nav

/**
 * 液态玻璃模糊策略（收尾项 4）。
 *
 * API 31+ 走 RenderEffect 对背板做真模糊；低版本 RenderEffect 不可用，
 * 保留「半透明 + 高光 + 描边」的仿制方案。参数集中在这里，UI 只读常量。
 */
const val GLASS_BLUR_MIN_API = 31

/** 真模糊半径（dp）。 */
const val GLASS_BLUR_RADIUS_DP = 24f

/** 有真模糊时底色可以更透，因为细节已被糊掉；低版本必须更实来压住内容。 */
const val GLASS_SCRIM_ALPHA_BLURRED = 0.42f

fun supportsRealBackdropBlur(apiLevel: Int): Boolean = apiLevel >= GLASS_BLUR_MIN_API

fun glassBlurRadiusDp(apiLevel: Int): Float =
    if (supportsRealBackdropBlur(apiLevel)) GLASS_BLUR_RADIUS_DP else 0f

fun glassScrimAlpha(apiLevel: Int): Float =
    if (supportsRealBackdropBlur(apiLevel)) GLASS_SCRIM_ALPHA_BLURRED else NAV_BAR_GLASS_ALPHA
