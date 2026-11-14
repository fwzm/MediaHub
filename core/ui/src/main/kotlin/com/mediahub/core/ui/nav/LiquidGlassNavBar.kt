package com.mediahub.core.ui.nav

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃导航栏（条目 29，关联 55 首页滚动性能 / 70 菜单高亮）。
 *
 * 玻璃质感刻意不用 RenderEffect / blur：低版本与低端机不会掉帧，
 * 只用「半透明底色 + 顶部高光渐变 + 细描边 + 阴影」四层叠加。
 * 高亮指示器位置由 [navSelectedFraction] 计算，动画只改偏移，不改布局尺寸，
 * 因此不会触发反复 measure（滚动时也稳定）。
 */
@Composable
fun LiquidGlassNavBar(
    items: List<NavBarItemSpec>,
    selectedId: String,
    onSelect: (String) -> Unit,
    iconOf: (NavBarItemSpec) -> ImageVector,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val selectedIndex = navSelectedIndex(items, selectedId)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NAV_BAR_CORNER_DP.dp),
        color = colors.surfaceContainer.copy(alpha = NAV_BAR_GLASS_ALPHA),
        contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
        tonalElevation = 0.dp,
        shadowElevation = 12.dp,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(NAV_BAR_HEIGHT_DP.dp)) {
            // 顶部高光：模拟液体玻璃边缘的折射带。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NAV_BAR_HEIGHT_DP.dp / 2)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                colors.primary.copy(alpha = NAV_BAR_SHEEN_ALPHA),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val slot = maxWidth / items.size
                val target = navSelectedFraction(selectedIndex, selectedIndex, items.size)
                val fraction by animateFloatAsState(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 220),
                    label = "navHighlightOffset",
                )
                Box(
                    modifier = Modifier
                        .offset(x = slot * fraction)
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                        .width(slot - 16.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.16f)),
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    items.forEachIndexed { index, item ->
                        val highlighted = index == selectedIndex
                        val emphasis by animateFloatAsState(
                            targetValue = navItemEmphasis(index, selectedIndex),
                            animationSpec = tween(durationMillis = 220),
                            label = "navEmphasis$index",
                        )
                        val tint = if (highlighted) colors.primary else colors.onSurface.copy(alpha = emphasis)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(NAV_BAR_CORNER_DP.dp))
                                .clickable { onSelect(item.id) }
                                .semantics { selected = highlighted },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = iconOf(item),
                                contentDescription = item.label,
                                tint = tint,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = tint,
                                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}
