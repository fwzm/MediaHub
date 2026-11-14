package com.mediahub.feature.player.danmaku

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mediahub.core.danmaku.DanmakuEpisode
import com.mediahub.feature.player.R

object DanmakuUiTestTags {
    const val COUNT_BADGE: String = "danmaku_count_badge"
    const val PANEL: String = "danmaku_panel"
    const val ENABLE_SWITCH: String = "danmaku_enable_switch"
    const val ONLINE_SWITCH: String = "danmaku_online_switch"
    const val OPACITY_SLIDER: String = "danmaku_opacity_slider"
    const val STROKE_SLIDER: String = "danmaku_stroke_slider"
    const val SEARCH_INPUT: String = "danmaku_search_input"
    const val SEARCH_BUTTON: String = "danmaku_search_button"
    const val INPUT: String = "danmaku_input"
    const val SEND: String = "danmaku_send"
    const val NOTICE: String = "danmaku_notice"

    fun searchResult(episodeId: String): String = "danmaku_search_result_$episodeId"
}

/** 控制层上的弹幕数量徽标（条目 101），点击打开弹幕面板。 */
@Composable
fun DanmakuCountBadge(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.testTag(DanmakuUiTestTags.COUNT_BADGE),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.player_danmaku),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = DanmakuCountFormatter.format(count),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * 弹幕面板：开关、透明度、描边、在线搜索、发送与数量。
 * 只做 UI 与输入校验，网络/持久化由 PlayerViewModel + DanmakuRepository 负责。
 */
@Composable
fun DanmakuPanel(
    state: DanmakuUiState,
    onEnabledChange: (Boolean) -> Unit,
    onOnlineEnabledChange: (Boolean) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onStrokeWidthChange: (Float) -> Unit,
    onSearch: (String) -> Unit,
    onEpisodeSelected: (DanmakuEpisode) -> Unit,
    onSend: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(DanmakuUiTestTags.PANEL)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_danmaku), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.player_danmaku_loaded, state.countLabel),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.player_danmaku_refresh))
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_danmaku_enable), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag(DanmakuUiTestTags.ENABLE_SWITCH),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_danmaku_online), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state.onlineEnabled,
                onCheckedChange = onOnlineEnabledChange,
                modifier = Modifier.testTag(DanmakuUiTestTags.ONLINE_SWITCH),
            )
        }

        Text(
            stringResource(R.string.player_danmaku_opacity, (state.opacity * 100f).toInt()),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = state.opacity,
            onValueChange = onOpacityChange,
            valueRange = DanmakuDefaults.MIN_OPACITY..1f,
            steps = 8,
            enabled = state.enabled,
            modifier = Modifier.testTag(DanmakuUiTestTags.OPACITY_SLIDER),
        )

        Text(
            stringResource(R.string.player_danmaku_stroke, state.strokeWidthDp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = state.strokeWidthDp,
            onValueChange = onStrokeWidthChange,
            valueRange = 0f..DanmakuDefaults.MAX_STROKE_WIDTH_DP,
            steps = 5,
            enabled = state.enabled,
            modifier = Modifier.testTag(DanmakuUiTestTags.STROKE_SLIDER),
        )

        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.player_danmaku_loading), style = MaterialTheme.typography.bodySmall)
            }
        }

        state.sourceLabel?.let { source ->
            Text(
                stringResource(R.string.player_danmaku_source, source),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.notice?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(DanmakuUiTestTags.NOTICE),
            )
        }

        HorizontalDivider()

        var searchText by remember(state.searchQuery) { mutableStateOf(state.searchQuery) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                singleLine = true,
                label = { Text(stringResource(R.string.player_danmaku_search_hint)) },
                modifier = Modifier
                    .weight(1f)
                    .testTag(DanmakuUiTestTags.SEARCH_INPUT),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSearch(searchText) },
                enabled = searchText.isNotBlank() && !state.searching,
                modifier = Modifier.testTag(DanmakuUiTestTags.SEARCH_BUTTON),
            ) {
                Text(stringResource(R.string.player_danmaku_search_action))
            }
        }

        if (!state.searching && state.searchResults.isEmpty() && searchText.isNotBlank()) {
            Text(
                stringResource(R.string.player_danmaku_search_empty),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.searchResults.forEach { episode ->
            Text(
                text = stringResource(
                    R.string.player_danmaku_search_result,
                    episode.animeTitle,
                    episode.episodeTitle,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DanmakuUiTestTags.searchResult(episode.id))
                    .clickable { onEpisodeSelected(episode) }
                    .padding(vertical = 8.dp),
            )
        }

        HorizontalDivider()

        var draft by remember { mutableStateOf("") }
        val validation = remember(draft) { DanmakuTextPolicy.validate(draft) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                isError = draft.isNotEmpty() && validation.error != null,
                label = { Text(stringResource(R.string.player_danmaku_input_hint)) },
                supportingText = validation.error
                    ?.takeIf { draft.isNotEmpty() }
                    ?.let { error ->
                        {
                            Text(
                                when (error) {
                                    DanmakuTextError.TOO_LONG -> stringResource(
                                        R.string.player_danmaku_send_too_long,
                                        DanmakuDefaults.MAX_TEXT_LENGTH,
                                    )

                                    DanmakuTextError.EMPTY -> stringResource(R.string.player_danmaku_send_empty)
                                },
                            )
                        }
                    },
                modifier = Modifier
                    .weight(1f)
                    .testTag(DanmakuUiTestTags.INPUT),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (validation.canSend) {
                        onSend(validation.normalized)
                        draft = ""
                    }
                },
                enabled = validation.canSend,
                modifier = Modifier.testTag(DanmakuUiTestTags.SEND),
            ) {
                Text(stringResource(R.string.player_danmaku_send))
            }
        }
    }
}

/** 播放页弹幕 Bottom Sheet：与播放器的其它设置面板保持同一外观与交互。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DanmakuSheet(
    state: DanmakuUiState,
    onEnabledChange: (Boolean) -> Unit,
    onOnlineEnabledChange: (Boolean) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onStrokeWidthChange: (Float) -> Unit,
    onSearch: (String) -> Unit,
    onEpisodeSelected: (DanmakuEpisode) -> Unit,
    onSend: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        DanmakuPanel(
            state = state,
            onEnabledChange = onEnabledChange,
            onOnlineEnabledChange = onOnlineEnabledChange,
            onOpacityChange = onOpacityChange,
            onStrokeWidthChange = onStrokeWidthChange,
            onSearch = onSearch,
            onEpisodeSelected = onEpisodeSelected,
            onSend = onSend,
            onRefresh = onRefresh,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}
