package com.mediahub.feature.library.favorites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 「我的收藏」列表（task-25 / A2）：server → handle.favorite.listFavorites。 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val registry: MediaProviderRegistry,
    private val logger: Logger,
) : ViewModel() {

    val serverId: String = checkNotNull(savedStateHandle["serverId"])

    private val _uiState = MutableStateFlow<FavoritesUiState>(FavoritesUiState.Loading)
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        val previous = _uiState.value
        // 已有内容时刷新不清空列表，失败也保留（只挂 refreshError）
        if (previous !is FavoritesUiState.Content) _uiState.value = FavoritesState.loading()
        viewModelScope.launch {
            try {
                val server = serverStore.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val handle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                val favoriteProvider = handle.favorite
                    ?: throw ProviderException.NotYetImplemented(serverId, "该数据源的收藏能力尚未接入")
                _uiState.value = FavoritesState.loaded(favoriteProvider.listFavorites())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.w(LogTag.UI, "收藏列表加载失败 serverId=$serverId", e)
                _uiState.value = FavoritesState.failed(_uiState.value, userMessage(e))
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ProviderException -> e.message ?: "加载失败"
        else -> "加载失败"
    }
}
