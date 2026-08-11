package com.mediahub.app.di

import android.content.Context
import com.mediahub.provider.local.LocalRootProvider
import java.io.File

/**
 * 本地存储根目录：
 * Phase 0 使用应用私有外部目录（无需权限）；完整"本机存储"需接入 SAF 文档树（后续阶段）。
 */
class AppLocalRootProvider(context: Context) : LocalRootProvider {

    private val roots: List<File> = buildList {
        context.getExternalFilesDir(null)?.let { add(it) }
    }

    override fun rootDirectories(): List<File> = roots
}
