package com.mediahub.provider.local

import java.io.File

/**
 * 本地存储根目录提供者。
 * Phase 0 使用应用私有外部目录；完整"本机存储"需接入 SAF 文档树（后续阶段）。
 */
interface LocalRootProvider {
    fun rootDirectories(): List<File>
}
