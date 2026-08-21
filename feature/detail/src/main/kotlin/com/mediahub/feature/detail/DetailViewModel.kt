package com.mediahub.feature.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.Logger
import com.mediahub.core.logging.LogTag
import com.mediahub.model.MediaDetail
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 详情页（Phase 1B-2.3 极简版）：server → handle.detail.getItemDetail。
 * 展示 backdrop/海报/元信息/简介 + 播放入口；季/集与演职人员不在本期范围。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val registry: MediaProviderRegistry,
    private val logger: Logger,
) : ViewModel() {

    val serverId: String = checkNotNull(savedStateHandle["serverId"])

    // itemId 经 NavArgCodec(Base64 URL_SAFE) 传输，兼容文件路径中的 '/'（与 PlayerViewModel 一致）
    val itemId: String = NavArgCodec.decode(checkNotNull(savedStateHandle["itemId"]))

    private val _uiState = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.value = DetailUiState.Loading
        viewModelScope.launch {
            try {
                val server = serverStore.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val handle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                val detailProvider = handle.detail
                    ?: throw ProviderException.NotYetImplemented(serverId, "该数据源的详情能力尚未接入")
                val detail = detailProvider.getItemDetail(itemId)
                _uiState.value = DetailUiState.Content(detail)
            } catch (e: Exception) {
                logger.w(LogTag.UI, "详情加载失败 serverId=$serverId itemId=$itemId", e)
                _uiState.value = DetailUiState.Error(userMessage(e))
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ProviderException -> e.message ?: "加载失败"
        else -> "加载失败：${e.message}"
    }
}

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Content(val detail: MediaDetail) : DetailUiState
    data class Error(val message: String) : DetailUiState
}
