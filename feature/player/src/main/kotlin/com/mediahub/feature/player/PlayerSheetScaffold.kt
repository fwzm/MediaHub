package com.mediahub.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 审计 P11：播放页四个 Bottom Sheet（音轨 / 字幕 / 播放信息 / 特效）共用的骨架。
 *
 * 统一 ModalBottomSheet 的容器色、内容色、拖拽把手色，以及内容区的水平 20dp、
 * 底部 32dp 内边距和可选纵向滚动。各 sheet 只负责自己的标题和正文，不再各自
 * 手写一套 ModalBottomSheet + Column 参数。
 *
 * 特效 sheet 的内容是自带滚动与内边距的 [com.mediahub.core.ui.effects.PlayerVisualSettingsPanel]，
 * 因此允许 scrollable = false 且传空白 contentPadding，避免双重滚动/双重留白。
 */
internal const val PLAYER_SHEET_HORIZONTAL_PADDING_DP = 20
internal const val PLAYER_SHEET_BOTTOM_PADDING_DP = 32

internal fun playerSheetContentPadding(): PaddingValues = PaddingValues(
    start = PLAYER_SHEET_HORIZONTAL_PADDING_DP.dp,
    end = PLAYER_SHEET_HORIZONTAL_PADDING_DP.dp,
    bottom = PLAYER_SHEET_BOTTOM_PADDING_DP.dp,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheetScaffold(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    dragHandleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    scrollable: Boolean = true,
    contentPadding: PaddingValues = playerSheetContentPadding(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = containerColor,
        contentColor = contentColor,
        dragHandle = { BottomSheetDefaults.DragHandle(color = dragHandleColor) },
    ) {
        PlayerSheetContent(
            scrollable = scrollable,
            contentPadding = contentPadding,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** 可独立测试的共享内容区：真实 sheet 与 Robolectric 测试使用同一份布局。 */
@Composable
internal fun PlayerSheetContent(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    contentPadding: PaddingValues = playerSheetContentPadding(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth()
    Column(
        modifier = if (scrollable) {
            base.verticalScroll(rememberScrollState()).padding(contentPadding)
        } else {
            base.padding(contentPadding)
        },
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
