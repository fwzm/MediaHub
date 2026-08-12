package com.mediahub.provider.local

import android.net.Uri
import java.io.File

/**
 * 本地存储根目录提供者。
 *
 * - [rootDirectories]：file 型根（当前为应用外部目录）。
 * - [contentRoots]：SAF 文档树根（content:// tree，见 ADR-020）。
 *   Phase 0.5 仅预留接口；目录选择器（ACTION_OPEN_DOCUMENT_TREE）与
 *   DocumentFile 树导航在 Phase 0.6 接入。
 */
interface LocalRootProvider {
    fun rootDirectories(): List<File>

    fun contentRoots(): List<Uri> = emptyList()
}
