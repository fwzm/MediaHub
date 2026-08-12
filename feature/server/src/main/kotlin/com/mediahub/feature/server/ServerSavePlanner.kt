package com.mediahub.feature.server

import com.mediahub.model.MediaServer

/**
 * 服务器保存决策（纯函数，便于 JVM 单测；评审 Patch 2：existing re-login 不产生重复服务器）。
 *
 * 语义：
 * - [plan] 当存在 [existing] 时走"更新"（updateSource=true，复用 same id 并保留元数据）；
 *   否则走"新建"（updateSource=false）。
 * - 不允许 addServer 创建重复：existing ≠ null 时总是复用其 id。
 */
internal object ServerSavePlanner {

    data class SaveDecision(
        val server: MediaServer,
        val updateSource: Boolean,
    )

    fun plan(
        existing: MediaServer?,
        candidate: MediaServer,
    ): SaveDecision {
        return if (existing != null) {
            // re-login / re-authorized：复用 same id 与元数据，走 updateServer
            SaveDecision(
                server = candidate.copy(
                    id = existing.id,
                    isDefault = existing.isDefault,
                    sortOrder = existing.sortOrder,
                    createdAtEpochMs = existing.createdAtEpochMs,
                    lastConnectedAtEpochMs = existing.lastConnectedAtEpochMs,
                    lastError = existing.lastError,
                ),
                updateSource = true,
            )
        } else {
            SaveDecision(server = candidate, updateSource = false)
        }
    }
}
