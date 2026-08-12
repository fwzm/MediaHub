package com.mediahub.feature.server

import com.mediahub.model.MediaServer
import com.mediahub.provider.api.ProviderDescriptor

/**
 * Existing-server 编辑持久化策略（评审 FINAL PATCH 2/3）。
 *
 * - descriptor 按 [ServerType] 匹配（禁止用 enum.name 当 ProviderDescriptor.id）。
 * - existing 模式复用 SAME id 并完整保留元数据（updateServer，不 addServer 重复）。
 *
 * 备注：卡片"点击是否进入重新登录"的判定已收敛到 feature:home 的
 * [AuthNavigationPolicy]（单一 source of truth，FINAL PATCH 3）。
 */
internal object ExistingServerEditPolicy {

    data class Draft(
        val server: MediaServer,
        val updateSource: Boolean,
    )

    /** 按 serverType 找 descriptor（不匹配返回 null，调用方应显示错误）。 */
    fun descriptorFor(server: MediaServer, descriptors: List<ProviderDescriptor>): ProviderDescriptor? =
        descriptors.firstOrNull { it.serverType == server.type }

    /** 构造待保存草稿：existing 复用原 id + 完整元数据；新建走候选。 */
    fun buildDraft(existing: MediaServer?, candidate: MediaServer): Draft {
        return if (existing != null) {
            Draft(
                server = candidate.copy(
                    id = existing.id,
                    type = existing.type,
                    isDefault = existing.isDefault,
                    sortOrder = existing.sortOrder,
                    createdAtEpochMs = existing.createdAtEpochMs,
                    lastConnectedAtEpochMs = existing.lastConnectedAtEpochMs,
                    lastError = existing.lastError,
                ),
                updateSource = true,
            )
        } else {
            Draft(server = candidate, updateSource = false)
        }
    }
}
