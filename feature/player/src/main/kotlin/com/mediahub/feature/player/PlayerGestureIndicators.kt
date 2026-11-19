package com.mediahub.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mediahub.feature.player.gesture.GestureLevelPreview
import com.mediahub.feature.player.gesture.GestureSeekPreview
import com.mediahub.feature.player.gesture.GestureSpeedPreview
import com.mediahub.feature.player.gesture.PlayerLevelKind
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放页手势指示器统一容器（UI 视觉审计 P7）。
 *
 * 审计结论：seek 卡与速度卡各自是 0.65 黑底直角块，音量/亮度条是 0.6 黑底 28dp 圆角胶囊，
 * 同一交互层出现三套皮肤。此处收敛为单一容器 token，三个指示器只提供各自内容。
 */
internal const val PLAYER_GESTURE_INDICATOR_TAG = "player_gesture_indicator"

internal data class PlayerGestureIndicatorStyle(
    val background: Color,
    val cornerRadiusDp: Float,
    val horizontalPaddingDp: Float,
    val verticalPaddingDp: Float,
)

internal fun playerGestureIndicatorStyle(): PlayerGestureIndicatorStyle =
    PlayerGestureIndicatorStyle(
        background = Color.Black.copy(alpha = 0.72f),
        cornerRadiusDp = 16f,
        horizontalPaddingDp = 20f,
        verticalPaddingDp = 12f,
    )

@Composable
internal fun PlayerGestureIndicatorSurface(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Center,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = playerGestureIndicatorStyle()
    Column(
        modifier = modifier
            .testTag(PLAYER_GESTURE_INDICATOR_TAG)
            .background(style.background, RoundedCornerShape(style.cornerRadiusDp.dp))
            .padding(
                horizontal = style.horizontalPaddingDp.dp,
                vertical = style.verticalPaddingDp.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
internal fun GestureSeekIndicator(preview: GestureSeekPreview, modifier: Modifier = Modifier) {
    val sign = if (preview.deltaMs >= 0) "+" else "-"
    val absSeconds = abs(preview.deltaMs) / 1000
    val deltaText = "$sign%d:%02d".format(absSeconds / 60, absSeconds % 60)
    PlayerGestureIndicatorSurface(modifier = modifier) {
        Text(
            text = stringResource(R.string.player_gesture_seek, deltaText, formatTime(preview.targetPositionMs)),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
internal fun GestureSpeedIndicator(preview: GestureSpeedPreview, modifier: Modifier = Modifier) {
    val text = stringResource(
        if (preview.isRewind) R.string.player_gesture_rewinding else R.string.player_gesture_speed,
        preview.speed,
    )
    PlayerGestureIndicatorSurface(modifier = modifier) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
internal fun GestureLevelIndicator(preview: GestureLevelPreview, modifier: Modifier = Modifier) {
    val icon = if (preview.kind == PlayerLevelKind.BRIGHTNESS) Icons.Default.LightMode else Icons.Default.VolumeUp
    PlayerGestureIndicatorSurface(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(
            icon,
            contentDescription = stringResource(
                if (preview.kind == PlayerLevelKind.BRIGHTNESS) R.string.player_brightness else R.string.player_volume,
            ),
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(140.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.25f)),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(preview.fraction.coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White),
            )
        }
        Text(
            stringResource(R.string.player_level_percent, (preview.fraction * 100).roundToInt()),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
