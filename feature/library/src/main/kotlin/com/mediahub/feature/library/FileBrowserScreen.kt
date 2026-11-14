package com.mediahub.feature.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediahub.core.ui.MediaHubCard
import com.mediahub.core.ui.MediaHubDialog
import com.mediahub.core.ui.MediaHubStatus
import com.mediahub.core.ui.MediaLogo
import com.mediahub.model.FileBrowserEntry
import com.mediahub.model.MediaItem
import com.mediahub.model.formatFileSize
import com.mediahub.model.toFileBrowserEntry

/**
 * 文件浏览器（条目 108/110）。
 *
 * 复用 [LibraryViewModel] 的目录数据（同一 serverId/libraryId 导航参数），
 * 只提供「看文件」的独立页面骨架：大图 / 标准 / 列表三档 + 标题换行。
 */
@Composable
fun FileBrowserRoute(
    title: String,
    onBack: () -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
    safViewModel: SafBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val safState by safViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            safViewModel.openTree(uri.toString())
        }
    }
    FileBrowserContent(
        state = state,
        fallbackTitle = title,
        onBack = onBack,
        onOpenFolder = viewModel::openFolder,
        onOpenItem = onOpenItem,
        onGoToParent = viewModel::goToParent,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::load,
        safState = safState,
        onPickFolder = { folderPicker.launch(null) },
        onEnterSafEntry = safViewModel::enter,
        onSafGoUp = safViewModel::goUp,
        onSafRetry = safViewModel::reload,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserContent(
    state: LibraryUiState,
    fallbackTitle: String,
    onBack: () -> Unit,
    onOpenFolder: (MediaItem) -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    onGoToParent: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    safState: SafBrowserUiState = SafBrowserUiState(),
    onPickFolder: () -> Unit = {},
    onEnterSafEntry: (FileBrowserEntry) -> Unit = {},
    onSafGoUp: () -> Unit = {},
    onSafRetry: () -> Unit = {},
) {
    var mode by rememberSaveable { mutableStateOf(LibraryViewMode.LARGE) }
    var safMode by rememberSaveable { mutableStateOf(false) }
    val libraryTitle = when (state) {
        is LibraryUiState.Content -> state.libraryName.ifBlank { fallbackTitle }
        is LibraryUiState.Libraries -> state.libraryName.ifBlank { fallbackTitle }
        else -> fallbackTitle
    }
    val title = if (safMode) {
        safState.stack.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "本地文件夹"
    } else {
        libraryTitle
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { "文件浏览" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (safMode) {
                                safMode = false
                            } else {
                                safMode = true
                                if (!safState.hasRoot) onPickFolder()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = if (safMode) "退出本地文件夹" else "浏览器独立目录栈",
                        )
                    }
                    if (!safMode) {
                        IconButton(
                            onClick = {
                                mode = when (mode) {
                                    LibraryViewMode.LARGE -> LibraryViewMode.STANDARD
                                    LibraryViewMode.STANDARD -> LibraryViewMode.LIST
                                    LibraryViewMode.LIST -> LibraryViewMode.LARGE
                                }
                            },
                        ) {
                            Icon(
                                imageVector = if (mode == LibraryViewMode.LIST) {
                                    Icons.Default.GridView
                                } else {
                                    Icons.Default.ViewList
                                },
                                contentDescription = "切换文件浏览器视图",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (safMode) {
            SafDirectoryPanel(
                state = safState,
                modifier = Modifier.fillMaxSize().padding(padding),
                onPickFolder = onPickFolder,
                onEnter = onEnterSafEntry,
                onGoUp = onSafGoUp,
                onRetry = onSafRetry,
            )
        } else when (state) {
            LibraryUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is LibraryUiState.Error -> MediaHubStatus(
                title = "无法打开此目录",
                message = state.message,
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                retryLabel = "重试",
                onRetry = onRetry,
            )

            is LibraryUiState.Libraries -> MediaHubStatus(
                title = "这里是媒体库列表",
                message = "请先进入一个媒体库后再浏览文件。",
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            )

            is LibraryUiState.Content -> {
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                    val columns = fileBrowserColumns(maxWidth.value, mode)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.canGoUp) {
                            item(span = { GridItemSpan(columns) }) {
                                TextButton(onClick = onGoToParent, modifier = Modifier.fillMaxWidth()) {
                                    Text("⬆ 返回上级")
                                }
                            }
                        }
                        gridItems(state.items, key = { it.id }) { item ->
                            val entry = item.toFileBrowserEntry()
                            FileEntryCell(
                                entry = entry,
                                mode = mode,
                                onClick = {
                                    if (item.type == com.mediahub.model.MediaType.FOLDER) {
                                        onOpenFolder(item)
                                    } else {
                                        onOpenItem(item)
                                    }
                                },
                            )
                        }
                        if (state.hasMore) {
                            item(span = { GridItemSpan(columns) }) {
                                TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafDirectoryPanel(
    state: SafBrowserUiState,
    modifier: Modifier,
    onPickFolder: () -> Unit,
    onEnter: (FileBrowserEntry) -> Unit,
    onGoUp: () -> Unit,
    onRetry: () -> Unit,
) {
    var preview by remember { mutableStateOf<FileBrowserEntry?>(null) }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = state.breadcrumb,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.canGoUp) {
                TextButton(onClick = onGoUp) { Text("上级") }
            }
            TextButton(onClick = onPickFolder) { Text("换目录") }
        }
        when {
            !state.hasRoot -> MediaHubStatus(
                title = "还没有选择文件夹",
                message = "从系统目录选择器授权一个文件夹后，可逐级进出并保留路径。",
                modifier = Modifier.fillMaxSize(),
                retryLabel = "选择文件夹",
                onRetry = onPickFolder,
            )

            state.loading && state.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.error != null -> MediaHubStatus(
                title = "无法读取该目录",
                message = state.error,
                modifier = Modifier.fillMaxSize(),
                retryLabel = "重试",
                onRetry = onRetry,
            )

            else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val columns = fileBrowserColumns(maxWidth.value, LibraryViewMode.LARGE)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(state.entries, key = { it.id }) { entry ->
                        FileEntryCell(
                            entry = entry,
                            mode = LibraryViewMode.LARGE,
                            onClick = {
                                if (entry.isDirectory) onEnter(entry) else preview = entry
                            },
                        )
                    }
                }
            }
        }
    }
    preview?.let { entry ->
        MediaHubDialog(
            title = entry.name,
            onDismiss = { preview = null },
            message = listOfNotNull(
                if (entry.isDirectory) "文件夹" else "文件",
                formatFileSize(entry.sizeBytes).takeIf { it.isNotEmpty() },
                entry.path,
            ).joinToString("\n"),
            confirmLabel = "关闭",
            dismissLabel = null,
            onConfirm = { preview = null },
        )
    }
}

@Composable
private fun FileEntryCell(
    entry: FileBrowserEntry,
    mode: LibraryViewMode,
    onClick: () -> Unit,
) {
    val maxLines = fileBrowserTitleMaxLines(mode)
    val sizeLabel = formatFileSize(entry.sizeBytes)
    if (mode == LibraryViewMode.LIST) {
        MediaHubCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            contentPadding = PaddingValues(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MediaLogo(
                    url = null,
                    contentDescription = null,
                    modifier = Modifier.size(LibraryLogoSize),
                    fallbackIcon = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.ViewList,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
                    if (sizeLabel.isNotEmpty()) {
                        Text(
                            sizeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    } else {
        MediaHubCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            contentPadding = PaddingValues(10.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MediaLogo(
                    url = null,
                    contentDescription = null,
                    modifier = Modifier.size(if (mode == LibraryViewMode.LARGE) 96.dp else 64.dp),
                    fallbackIcon = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.ViewList,
                )
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sizeLabel.isNotEmpty()) {
                    Text(
                        sizeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
