package com.mediahub.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.ui.MediaHubStatus
import com.mediahub.model.PlaybackProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 单资源「继续观看」的「查看全部」页（条目 121）。
 * 只读取本地进度快照，按首页所选来源过滤；空时明确提示 [NO_PLAYABLE_RESOURCES]（条目 148）。
 */
@HiltViewModel
class ContinueWatchingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    progressRepository: ProgressStore,
    logger: Logger,
) : ViewModel() {

    private val serverId: String = savedStateHandle.get<String>("serverId").orEmpty()
    val title: String = savedStateHandle.get<String>("label").orEmpty().ifBlank { "继续观看" }

    val items = progressRepository.observeContinueWatching(limit = 100)
        .map { scopedContinueWatching(it, serverId.ifBlank { null }) }
        .catch { error ->
            logger.w(LogTag.UI, "继续观看全部列表加载失败", error)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContinueWatchingRoute(
    onBack: () -> Unit,
    onOpenItem: (PlaybackProgress) -> Unit,
    viewModel: ContinueWatchingViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(viewModel.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    MediaHubStatus(
                        title = NO_PLAYABLE_RESOURCES,
                        message = "该数据源还没有可继续播放的内容。",
                        icon = Icons.Default.PlayArrow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                items(items, key = { "${it.serverId}/${it.itemId}" }) { progress ->
                    ContinueWatchingCard(
                        progress = progress,
                        onClick = { onOpenItem(progress) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
