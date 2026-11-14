package com.mediahub.core.ui.nav

/**
 * 液态玻璃导航栏策略（条目 29，关联 55 / 70）。
 *
 * 只放纯数值：玻璃层厚度、圆角、内外边距、高亮指示器的位置比例。
 * 组合层（[LiquidGlassNavBar]）只允许引用这里的常量，避免动效参数散落在 UI 里。
 */

/** 导航栏条目（id 用于选中态，label 用于文字）。 */
data class NavBarItemSpec(val id: String, val label: String)

const val NAV_BAR_HEIGHT_DP = 64
const val NAV_BAR_CORNER_DP = 28
const val NAV_BAR_INSET_DP = 16

/** 玻璃底色不透明度：够透能看见内容，够实能压住滚动内容。 */
const val NAV_BAR_GLASS_ALPHA = 0.72f

/** 顶部高光层不透明度，必须比玻璃底色更淡才能形成「玻璃边缘」。 */
const val NAV_BAR_SHEEN_ALPHA = 0.10f

/**
 * 选中项在整条导航栏中的位置比例（0 = 最左，1 = 最右）。
 * 指示器水平偏移 = 槽位宽 × 该比例，因此条目增删都不会错位。
 */
fun navSelectedFraction(index: Int, selected: Int, count: Int): Float {
    if (count <= 1) return 0f
    val safeIndex = index.coerceIn(0, count - 1)
    return safeIndex.toFloat() / (count - 1).toFloat()
}

/** 菜单高亮：选中项图标/文字满强度，未选中项压暗，但仍可读。 */
fun navItemEmphasis(index: Int, selected: Int): Float = if (index == selected) 1f else 0.62f

/** id → 下标；选中的 id 不存在时回落到第一项，避免指示器跑到栏外。 */
fun navSelectedIndex(items: List<NavBarItemSpec>, selectedId: String): Int {
    val found = items.indexOfFirst { it.id == selectedId }
    return if (found >= 0) found else 0
}
