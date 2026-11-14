package com.mediahub.feature.detail.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.ManualMatchRecord
import com.mediahub.model.manualMatchKey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 详情页的手动匹配回显（收尾项 1）：只读 DataStore 里的匹配映射，
 * 不依赖 `DetailViewModel`，避免与另一路正在改的匹配入口冲突。
 */
@HiltViewModel
class ManualMatchBadgeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val preferences: UserPreferencesRepository,
) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    private val itemId: String = NavArgCodec.decode(checkNotNull(savedStateHandle["itemId"]))
    private val key: String = manualMatchKey(serverId, itemId)

    val match: StateFlow<ManualMatchRecord?> = preferences.flow
        .map { it.manualMatches[key] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clearMatch() {
        viewModelScope.launch {
            preferences.update { prefs -> prefs.copy(manualMatches = prefs.manualMatches - key) }
        }
    }
}

/**
 * 「已手动匹配到 X」条幅：跨重启仍然显示，可打开匹配到的条目或取消匹配。
 */
@Composable
fun ManualMatchedBanner(
    onOpenTarget: (targetId: String, targetTitle: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManualMatchBadgeViewModel = hiltViewModel(),
) {
    val record = viewModel.match.collectAsStateWithLifecycle().value ?: return
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "已手动匹配",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    record.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { onOpenTarget(record.targetId, record.title) }) { Text("打开") }
            TextButton(onClick = viewModel::clearMatch) { Text("取消匹配") }
        }
    }
}
