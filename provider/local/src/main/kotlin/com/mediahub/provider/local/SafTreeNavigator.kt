package com.mediahub.provider.local

import android.content.Context
import android.provider.DocumentsContract

/**
 * SAF 文档树导航器（review P1 修复）。
 *
 * 关键约束：**绝不使用 [androidx.documentfile.provider.DocumentFile.fromSingleUri]**
 * 重新进入子目录——那会得到 single-document wrapper，无法 listFiles()，
 * 导致嵌套目录浏览失败。本导航器全程保持 **tree-backed**（[SafUri]）：
 * - 根：`treeDocId(treeUri)`；
 * - 子项：`childrenUri(treeUri, docId)` 查询；
 * - 每个子项 uri：`documentUri(treeUri, childDocId)`。
 *
 * [SafSource] 可注入，便于 JVM 单测（生产实现走 ContentResolver）。
 */
class SafTreeNavigator(
    private val treeUri: String,
    private val source: SafSource,
) {

    /** 文档树根 docId。 */
    fun rootDocId(): String = SafUri.treeDocId(treeUri)

    /** 列出某目录的直接子项（tree-backed uri）。 */
    fun listChildren(parentDocId: String): List<SafEntry> {
        val childrenUri = SafUri.childrenUri(treeUri, parentDocId)
        return source.listChildren(childrenUri).map { row ->
            SafEntry(
                uri = SafUri.documentUri(treeUri, row.docId),
                docId = row.docId,
                name = row.name,
                mimeType = row.mimeType,
                sizeBytes = row.sizeBytes,
            )
        }
    }

    /** 从已保存的 tree-backed document uri 提取 docId（进入子目录时用）。 */
    fun docIdOf(documentUri: String): String = SafUri.docId(documentUri)

    data class SafRow(
        val docId: String,
        val name: String,
        val mimeType: String?,
        val sizeBytes: Long?,
    )

    data class SafEntry(
        val uri: String,
        val docId: String,
        val name: String,
        val mimeType: String?,
        val sizeBytes: Long?,
    )

    /** 文档源抽象（生产 = ContentResolver，测试 = fake）。 */
    interface SafSource {
        fun listChildren(childrenUri: String): List<SafRow>
        fun canOpen(documentUri: String): Boolean
    }

    /** 生产实现：ContentResolver + DocumentsContract 查询。 */
    class ContentResolverSource(private val context: Context) : SafSource {

        override fun listChildren(childrenUri: String): List<SafRow> {
            val rows = mutableListOf<SafRow>()
            context.contentResolver.query(android.net.Uri.parse(childrenUri), null, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else null
                    rows += SafRow(
                        docId = cursor.getString(idCol),
                        name = cursor.getString(nameCol) ?: "",
                        mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null,
                        sizeBytes = size,
                    )
                }
            }
            return rows
        }

        override fun canOpen(documentUri: String): Boolean = runCatching {
            context.contentResolver.openFileDescriptor(android.net.Uri.parse(documentUri), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }
}
