package com.mediahub.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaItem
import com.mediahub.model.PageRequest
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Content(
        val items: List<MediaItem>,
        val libraryName: String,
        val currentFolder: MediaItem? = null,
        val canGoUp: Boolean = false,
    ) : LibraryUiState

    data class Error(val message: String) : LibraryUiState
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val registry: MediaProviderRegistry,
    private val logger: Logger,
) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    private val libraryId: String = checkNotNull(savedStateHandle["libraryId"])
    private val libraryName: String = savedStateHandle["name"] ?: ""

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var currentFolder: MediaItem? = null

    /** 文件夹导航栈（上级回溯）。 */
    private val folderStack = ArrayDeque<MediaItem?>()

    init {
        load()
    }

    fun openFolder(folder: MediaItem) {
        folderStack.addLast(currentFolder)
        currentFolder = folder
        load()
    }

    fun goToParent() {
        currentFolder = folderStack.removeLastOrNull()
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = LibraryUiState.Loading
            try {
                val server = serverRepository.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val handle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                val page = PageRequest(limit = 200)
                val browse = handle.browse
                val library = handle.library
                val result = when {
                    currentFolder != null && browse != null -> browse.listFolder(currentFolder, page)
                    library != null -> library.getItems(libraryId, page)
                    browse != null -> browse.listFolder(currentFolder, page)
                    else -> throw ProviderException.NotYetImplemented(serverId, "该媒体源浏览能力")
                }
                _uiState.value = LibraryUiState.Content(
                    items = result.items,
                    libraryName = libraryName.ifBlank { server.displayName },
                    currentFolder = currentFolder,
                    canGoUp = folderStack.isNotEmpty(),
                )
            } catch (e: Exception) {
                logger.w(LogTag.UI, "加载媒体库失败 serverId=$serverId", e)
                _uiState.value = LibraryUiState.Error(userMessage(e))
            }
        }
    }

    private fun userMessage(e: Exception): String = when (e) {
        is ProviderException -> e.message ?: "加载失败"
        else -> "加载失败：${e.message}"
    }
}
