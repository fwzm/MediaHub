package com.mediahub.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediahub.core.ui.MediaHubStatus

/**
 * 视觉审计 D2/D3：详情页的加载态与错误态原先各自手搓（裸 CircularProgressIndicator /
 * 裸 Text + TextButton），与首页、媒体库、搜索页的 MediaHubStatus 三态组件不一致。
 * 这里把两个状态收敛成一份纯数据描述，Composable 只负责把它交给 MediaHubStatus。
 */
internal const val DETAIL_STATUS_LOADING_TITLE = "正在载入详情"
internal const val DETAIL_STATUS_ERROR_TITLE = "详情暂时无法载入"
internal const val DETAIL_STATUS_ERROR_FALLBACK_MESSAGE = "请稍后重试，或返回上一页"
internal const val DETAIL_STATUS_RETRY_LABEL = "重试"

/** MediaHubStatus 的参数快照；内容态返回 null 表示不渲染任何状态块。 */
internal data class DetailStatusSpec(
    val title: String,
    val message: String? = null,
    val loading: Boolean = false,
    val retryLabel: String? = null,
)

internal fun detailStatusSpec(state: DetailUiState): DetailStatusSpec? = when (state) {
    DetailUiState.Loading -> DetailStatusSpec(
        title = DETAIL_STATUS_LOADING_TITLE,
        loading = true,
    )

    is DetailUiState.Error -> DetailStatusSpec(
        title = DETAIL_STATUS_ERROR_TITLE,
        message = state.message.trim().ifBlank { DETAIL_STATUS_ERROR_FALLBACK_MESSAGE },
        retryLabel = DETAIL_STATUS_RETRY_LABEL,
    )

    is DetailUiState.Content -> null
}

@Composable
internal fun DetailStatusPane(
    state: DetailUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = detailStatusSpec(state) ?: return
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        MediaHubStatus(
            title = spec.title,
            message = spec.message,
            icon = if (spec.loading) null else Icons.Default.Refresh,
            loading = spec.loading,
            retryLabel = spec.retryLabel,
            onRetry = if (spec.retryLabel != null) onRetry else null,
        )
    }
}
