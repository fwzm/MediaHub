package com.mediahub.core.common.sync

import com.mediahub.model.ServerType

/**
 * 把一份同步信封与"本机已有来源"变成建议列表（ADR-S001 D5）。纯函数：不碰 IO、不读时钟、不改入参。
 *
 * 规则按顺序施加于每条来源：
 * 1. 本端不认识 wire 值 → Unsupported，原始串保留在建议里（D3），将来版本可接管而不是让用户重录；
 * 2. 协议认识但本端没有该来源类型（Android 当前覆盖全部 12 个值，保留该分支以与 Windows 规则一致）→ Unsupported；
 * 3. 两个本地来源声称同一个 syncSourceId → 冲突，本机数据本身已不一致，谁也不许赢；
 * 4. 修订号更高 → Update；修订号相同且内容一致 → NoChange；相同但内容不同 → Conflict；更低 → IgnoreStale；
 * 5. 没有 syncSourceId 匹配时，pre-W6 的本地来源（无同步身份）只有在类型、规范化地址、账号三者都一致
 *    且唯一时才建议 Adopt；多个候选即冲突，合同从不猜（D5）。
 *
 * 本类中不出现任何墙上时间比较：createdAtEpochMs 与 deletedAtEpochMs 有意不读。两台设备的时钟互相矛盾，
 * 按时间戳做"后写获胜"会静默毁掉慢钟那一侧刚做的编辑（D5）。次序只来自 recordRevision，真正的平手交用户裁定。
 */
object SourceSyncPlanner {

    fun plan(envelope: SourceSyncEnvelope, locals: List<LocalSourceRecord>): SourceSyncPlan =
        plan(envelope, locals, { AndroidSourceTypeMap.resolve(it) }, { AndroidSourceTypeMap.toWire(it) })

    /**
     * 可注入类型解析的重载。生产路径用默认参数；测试可以注入一个"协议认识但本端没有类型"的解析结果，
     * 用来覆盖第 2 条规则——Android 的对照表覆盖全部 wire 值，该分支在生产数据上不可达。
     *
     * 公开而非 internal：可见性一旦依赖测试编译的 associate 配置，换个构建方式就会失败；
     * 这个重载本身也是"客户端自带映射表"的正当扩展点（wire 与类型对照不一定要走全局表）。
     */
    fun plan(
        envelope: SourceSyncEnvelope,
        locals: List<LocalSourceRecord>,
        resolve: (SyncSourceTypeValue) -> AndroidSourceTypeResolution,
        toWire: (ServerType?) -> String?,
    ): SourceSyncPlan {
        val items = ArrayList<SourceSyncCandidate>(envelope.sources.size + envelope.deletions.size)
        for (record in envelope.sources) {
            items.add(planRecord(record, locals, resolve, toWire))
        }
        for (tombstone in envelope.deletions) {
            items.add(planTombstone(tombstone, locals))
        }
        return SourceSyncPlan(items)
    }

    private fun planRecord(
        record: SourceRecord,
        locals: List<LocalSourceRecord>,
        resolve: (SyncSourceTypeValue) -> AndroidSourceTypeResolution,
        toWire: (ServerType?) -> String?,
    ): SourceSyncCandidate {
        val resolution = resolve(record.sourceType)
        val primaryUrl = primaryNormalizedUrl(record)
        val baseline = SourceSyncCandidate(
            action = SourceSyncAction.NO_CHANGE,
            syncSourceId = record.syncSourceId,
            sourceType = record.sourceType.raw,
            isKnownSourceType = resolution.isKnown,
            displayName = record.displayName,
            normalizedUrl = primaryUrl,
            remoteRevision = record.recordRevision,
        )

        if (!resolution.isKnown) {
            // 保留而不丢弃：原始 wire 串进入建议与本地存储（D3）。
            return baseline.copy(action = SourceSyncAction.UNSUPPORTED, reason = SourceSyncReasons.UNKNOWN_SOURCE_TYPE)
        }

        if (!resolution.isSupported) {
            // 协议里认识，但本端模型里没有（Windows 侧是 fn-nas 与四个云盘）。
            return baseline.copy(action = SourceSyncAction.UNSUPPORTED, reason = SourceSyncReasons.NOT_SUPPORTED_ON_WINDOWS)
        }

        val matches = locals.filter { sameSyncSourceId(it.syncSourceId, record.syncSourceId) }

        if (matches.size > 1) {
            return baseline.copy(
                action = SourceSyncAction.CONFLICT,
                reason = SourceSyncReasons.DUPLICATE_LOCAL_SYNC_ID,
                requiresUserApproval = true,
                candidateServerIds = matches.map { it.serverId },
            )
        }

        if (matches.size == 1) {
            val local = matches[0]
            if (record.recordRevision > local.recordRevision) {
                return baseline.copy(
                    action = SourceSyncAction.UPDATE,
                    reason = SourceSyncReasons.HIGHER_REVISION,
                    serverId = local.serverId,
                    localRevision = local.recordRevision,
                )
            }
            if (record.recordRevision == local.recordRevision) {
                val sameContent = contentMatches(record, resolution, local, primaryUrl, toWire)
                return baseline.copy(
                    action = if (sameContent) SourceSyncAction.NO_CHANGE else SourceSyncAction.CONFLICT,
                    reason = if (sameContent) SourceSyncReasons.UP_TO_DATE else SourceSyncReasons.SAME_REVISION_DIFFERENT_CONTENT,
                    serverId = local.serverId,
                    localRevision = local.recordRevision,
                    requiresUserApproval = !sameContent,
                )
            }
            return baseline.copy(
                action = SourceSyncAction.IGNORE_STALE,
                reason = SourceSyncReasons.STALE_REVISION,
                serverId = local.serverId,
                localRevision = local.recordRevision,
            )
        }

        // 没有同步身份匹配。pre-W6 的本地记录根本没有身份，因此只能"建议"，
        // 且必须三个身份要素全部一致、答案唯一（D5）。
        val legacy = legacyMatches(record, primaryUrl, locals, toWire)

        if (legacy.size == 1) {
            return baseline.copy(
                action = SourceSyncAction.ADOPT,
                reason = SourceSyncReasons.LEGACY_IDENTITY_MATCH,
                serverId = legacy[0].serverId,
                localRevision = legacy[0].recordRevision,
                requiresUserApproval = true,
            )
        }

        if (legacy.size > 1) {
            return baseline.copy(
                action = SourceSyncAction.CONFLICT,
                reason = SourceSyncReasons.AMBIGUOUS_LEGACY_IDENTITY,
                requiresUserApproval = true,
                candidateServerIds = legacy.map { it.serverId },
            )
        }

        if (primaryUrl == null) {
            // 新建一条没有可拨号地址的来源，用户除了删掉它无事可做。
            return baseline.copy(
                action = SourceSyncAction.CREATE,
                reason = SourceSyncReasons.NO_USABLE_ENDPOINT,
                requiresUserApproval = true,
            )
        }

        return baseline.copy(action = SourceSyncAction.CREATE, reason = SourceSyncReasons.NEW_SOURCE)
    }

    private fun legacyMatches(
        record: SourceRecord,
        primaryUrl: String?,
        locals: List<LocalSourceRecord>,
        toWire: (ServerType?) -> String?,
    ): List<LocalSourceRecord> {
        // 两个地址都必须有：只按类型与账号匹配会把同一用户用的两台同型服务器合并。
        if (primaryUrl == null) return emptyList()

        return locals.filter { local ->
            if (!local.syncSourceId.isNullOrBlank()) return@filter false
            val localWire = toWire(local.type) ?: return@filter false
            if (localWire != record.sourceType.wire) return@filter false
            if (!scopesEqual(local.accountScope, record.accountScope)) return@filter false
            SyncUrlNormalizer.normalize(local.url) == primaryUrl
        }
    }

    private fun planTombstone(tombstone: SourceTombstone, locals: List<LocalSourceRecord>): SourceSyncCandidate {
        val baseline = SourceSyncCandidate(
            action = SourceSyncAction.NO_CHANGE,
            syncSourceId = tombstone.syncSourceId,
            remoteRevision = tombstone.recordRevision,
        )

        val matches = locals.filter { sameSyncSourceId(it.syncSourceId, tombstone.syncSourceId) }

        if (matches.isEmpty()) {
            // 删掉本机从未有过的东西不是错误，也不是一个决定。
            return baseline.copy(action = SourceSyncAction.NO_CHANGE, reason = SourceSyncReasons.NO_LOCAL_SOURCE)
        }

        if (matches.size > 1) {
            return baseline.copy(
                action = SourceSyncAction.CONFLICT,
                reason = SourceSyncReasons.DUPLICATE_LOCAL_SYNC_ID,
                requiresUserApproval = true,
                candidateServerIds = matches.map { it.serverId },
            )
        }

        val match = matches[0]
        if (tombstone.recordRevision < match.recordRevision) {
            // 本地记录在远端删除之后又被编辑过：过时的删除不得提议移除仍然有效的数据。
            return baseline.copy(
                action = SourceSyncAction.IGNORE_STALE,
                reason = SourceSyncReasons.STALE_REVISION,
                serverId = match.serverId,
                localRevision = match.recordRevision,
            )
        }

        return baseline.copy(
            action = SourceSyncAction.DELETE,
            reason = SourceSyncReasons.TOMBSTONE,
            serverId = match.serverId,
            localRevision = match.recordRevision,
            // 永不静默：本地副本可能是最后一份，凭据也会跟着消失（D5、D6）。
            requiresUserApproval = true,
        )
    }

    private fun contentMatches(
        record: SourceRecord,
        resolution: AndroidSourceTypeResolution,
        local: LocalSourceRecord,
        primaryUrl: String?,
        toWire: (ServerType?) -> String?,
    ): Boolean {
        val serverType = resolution.serverType ?: return false
        if (toWire(serverType) != record.sourceType.wire) return false
        if (!scopesEqual(local.accountScope, record.accountScope)) return false
        // 显示名区分大小写：它是内容的一部分，不是身份。
        if ((local.displayName ?: "") != record.displayName) return false
        return SyncUrlNormalizer.normalize(local.url) == primaryUrl
    }

    private fun primaryNormalizedUrl(record: SourceRecord): String? {
        val endpoint = selectPrimaryEndpoint(record) ?: return null
        return SyncUrlNormalizer.normalize(endpoint.url)
    }

    /**
     * D2 只允许一个主端点。发送端标了多个时取 sortOrder 最小者；sortedBy 是稳定排序，
     * 相等时保持发送端顺序——而不是任意挑一个。
     */
    fun selectPrimaryEndpoint(record: SourceRecord): SourceEndpoint? {
        if (record.endpoints.isEmpty()) return null
        val primary = record.endpoints
            .filter { it.isPrimary }
            .sortedBy { it.sortOrder }
            .firstOrNull()
        return primary
            ?: record.endpoints.sortedBy { it.sortOrder }.firstOrNull()
    }

    private fun sameSyncSourceId(left: String?, right: String?): Boolean =
        !left.isNullOrBlank() && !right.isNullOrBlank() && left.trim().equals(right.trim(), ignoreCase = true)

    private fun scopesEqual(left: String?, right: String?): Boolean =
        normalizeScope(left).equals(normalizeScope(right), ignoreCase = true)

    private fun normalizeScope(accountScope: String?): String? =
        if (accountScope.isNullOrBlank()) null else accountScope.trim()
}
