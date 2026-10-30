package com.mediahub.app.di

import android.content.Context
import com.mediahub.provider.local.LocalRootProvider
import java.io.File

/**
 * 本地存储根目录：
 * 历史来源保留应用外部私有目录；SAF 来源只使用已持久保留的读授权。
 */
class AppLocalRootProvider(private val context: Context) : LocalRootProvider,
    com.mediahub.provider.local.SafTreeNavigator.SafSource {
    private val documents = com.mediahub.provider.local.SafTreeNavigator.ContentResolverSource(context)

    private val roots: List<File> = buildList {
        context.getExternalFilesDir(null)?.let { add(it) }
    }

    override fun rootDirectories(): List<File> = roots
    override fun contentRoots(): List<android.net.Uri> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && android.provider.DocumentsContract.isTreeUri(it.uri) }.map { it.uri }
    override fun listChildren(childrenUri: String) = documents.listChildren(childrenUri)
    override fun canOpen(documentUri: String) = documents.canOpen(documentUri)
    override fun metadata(documentUri: String) = documents.metadata(documentUri)
    override fun parentUri(documentUri: String) = documents.parentUri(documentUri)
}
