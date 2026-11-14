package com.mediahub.feature.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.model.FileBrowserEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SAF 目录读取抽象（收尾项 2）：只做「列子项」，便于在 JVM 上替换假实现。
 */
interface SafDocumentSource {
    suspend fun displayName(treeUri: String): String?

    suspend fun listChildren(treeUri: String, parentUri: String): List<FileBrowserEntry>
}

/**
 * 基于 `DocumentsContract` 的真实 SAF 实现（不新增 documentfile 依赖）。
 */
class AndroidSafDocumentSource(
    @ApplicationContext private val context: Context,
) : SafDocumentSource {

    override suspend fun displayName(treeUri: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val tree = Uri.parse(treeUri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            context.contentResolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    override suspend fun listChildren(
        treeUri: String,
        parentUri: String,
    ): List<FileBrowserEntry> = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val parentDocumentId = runCatching {
            DocumentsContract.getDocumentId(Uri.parse(parentUri))
        }.getOrElse { DocumentsContract.getTreeDocumentId(tree) }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocumentId)
        val entries = mutableListOf<FileBrowserEntry>()
        context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0) ?: continue
                val name = cursor.getString(1).orEmpty().ifBlank { documentId }
                val mimeType = cursor.getString(2).orEmpty()
                entries += FileBrowserEntry(
                    id = documentId,
                    name = name,
                    path = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString(),
                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = if (cursor.isNull(3)) null else cursor.getLong(3),
                    modifiedAtEpochMs = if (cursor.isNull(4)) null else cursor.getLong(4),
                )
            }
        }
        entries.sortedWith(
            compareByDescending<FileBrowserEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() },
        )
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** SAF 浏览状态：栈 + 当前层子项 + 加载/错误。 */
data class SafBrowserUiState(
    val stack: List<SafPathNode> = emptyList(),
    val entries: List<FileBrowserEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasRoot: Boolean get() = stack.isNotEmpty()

    val breadcrumb: String get() = SafPath.breadcrumb(stack)

    val canGoUp: Boolean get() = stack.size > 1
}

/**
 * 独立 SAF 目录栈（收尾项 2）：进出子目录、保留路径，并把栈写入 SavedStateHandle，
 * 旋转/进程重建后回到同一目录。
 */
@HiltViewModel
class SafBrowserViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val source: SafDocumentSource = AndroidSafDocumentSource(context)

    private val mutableState = MutableStateFlow(
        SafBrowserUiState(stack = SafPath.decode(savedStateHandle[KEY_STACK])),
    )
    val state: StateFlow<SafBrowserUiState> = mutableState.asStateFlow()

    init {
        if (mutableState.value.hasRoot) reload()
    }

    /** 用户通过系统目录选择器授权后进入该目录。 */
    fun openTree(treeUri: String) {
        viewModelScope.launch {
            val name = source.displayName(treeUri)?.takeIf { it.isNotBlank() } ?: "已选目录"
            applyStack(listOf(SafPathNode(uri = treeUri, name = name)))
        }
    }

    fun enter(entry: FileBrowserEntry) {
        val uri = entry.path ?: return
        applyStack(SafPath.push(mutableState.value.stack, SafPathNode(uri = uri, name = entry.name)))
    }

    fun goUp() {
        applyStack(SafPath.pop(mutableState.value.stack))
    }

    fun reload() {
        val stack = mutableState.value.stack
        val node = SafPath.current(stack) ?: return
        val treeUri = stack.first().uri
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            runCatching { source.listChildren(treeUri, node.uri) }
                .onSuccess { entries ->
                    mutableState.value = mutableState.value.copy(loading = false, entries = entries)
                }
                .onFailure { throwable ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        error = throwable.message ?: "无法读取该目录",
                    )
                }
        }
    }

    private fun applyStack(stack: List<SafPathNode>) {
        savedStateHandle[KEY_STACK] = SafPath.encode(stack)
        mutableState.value = SafBrowserUiState(stack = stack)
        if (stack.isNotEmpty()) reload()
    }

    private companion object {
        const val KEY_STACK = "safPathStack"
    }
}
