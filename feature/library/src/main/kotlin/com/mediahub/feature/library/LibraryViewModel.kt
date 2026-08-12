package com.mediahub.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaLibrary
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

    /** 顶层媒体库 Views（libraryId == "root"）。 */
    data class Libraries(
        val libraries: List<MediaLibrary>,
        val libraryName: String,
    ) : LibraryUiState

    /** 某媒体库内的条目 / 子级浏览。 */
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
    private val serverStore: ServerStore,
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
                val server = serverStore.getServer(serverId)
                    ?: throw ProviderException.NotFound(serverId, "媒体源")
                val handle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(serverId, "该媒体源类型")
                val page = PageRequest(limit = 200)
                // 能力组合（ADR-014）：媒体库型走 library，文件树型走 browse，均无则提示未接入。
                val library = handle.library
                val browse = handle.browse
                when {
                    // 顶层：显示媒体库 Views（评审 #7，不把 View 伪造成 MediaItem）
                    library != null && libraryId == "root" -> {
                        val libraries = library.getLibraries()
                        _uiState.value = LibraryUiState.Libraries(
                            libraries = libraries,
                            libraryName = libraryName.ifBlank { server.displayName },
                        )
                    }

                    library != null -> {
                        val parentId = currentFolder?.id ?: libraryId
                        val result = library.getItems(parentId, page)
                        _uiState.value = LibraryUiState.Content(
                            items = result.items,
                            libraryName = libraryName.ifBlank { server.displayName },
                            currentFolder = currentFolder,
                            canGoUp = folderStack.isNotEmpty(),
                        )
                    }

                    browse != null -> {
                        val result = browse.listFolder(currentFolder, page)
                        _uiState.value = LibraryUiState.Content(
                            items = result.items,
                            libraryName = libraryName.ifBlank { server.displayName },
                            currentFolder = currentFolder,
                            canGoUp = folderStack.isNotEmpty(),
                        )
                    }

                    else -> throw ProviderException.NotYetImplemented(serverId, "该数据源的浏览能力尚未接入")
                }
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
