package com.mediahub.core.common.sync

/** 一条来源里除主端点之外还有别的端点时给出的稳定原因码（对齐 Windows SourceSyncEndpointPolicy）。 */
object SourceSyncEndpointPolicyReasons {
    /** 这条来源还有未导入的其它端点；调用方可以把它们显示出来，而不是默默丢掉。 */
    const val SECONDARY_NOT_IMPORTED: String = "secondary-endpoint-not-imported"
}

/** 一条没有被导入的端点。 */
data class SourceSyncNotImportedEndpoint(
    val syncSourceId: String = "",
    val displayName: String = "",
    val url: String = "",
    /** 归一化后的地址；归一化不了就是 null（原始 [url] 仍保留）。 */
    val normalizedUrl: String? = null,
    val sortOrder: Int = 0,
    /** 这一条是否就是那条来源选出的主端点（重复行也算）。 */
    val isPrimary: Boolean = false,
    val enabled: Boolean = true,
    val reason: String = SourceSyncEndpointPolicyReasons.SECONDARY_NOT_IMPORTED,
)

/** 一次信封里「有多个端点 / 一个端点都没有」的统计。 */
data class SourceSyncEndpointSummary(
    val items: List<SourceSyncNotImportedEndpoint> = emptyList(),
    /** 有替代端点因而会丢东西的来源数。 */
    val recordsWithAlternatives: Int = 0,
    /** 端点表为空的来源数。 */
    val recordsWithoutEndpoint: Int = 0,
) {
    /** 没导入的端点条数。 */
    val notImportedCount: Int get() = items.size

    /** 没有任何可报告的事。 */
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * 多端点来源的选定与上报规则（W6-72，对齐 Windows SourceSyncEndpointPolicy）。
 *
 * 播放器只需要一个能播的地址，所以导入一侧永远只取主端点；但「其余端点被忽略」这件事必须能被看见，
 * 否则用户会以为对端那条来源只有一个地址。这里把两个决定分开：选谁进来（[selectPrimary]）与
 * 其余端点怎么报（[notImported]）——后者不影响写入，只影响界面与日志。
 */
object SourceSyncEndpointPolicy {

    /** 选定一条来源的主端点：优先标了 primary 且 sortOrder 最小的，没有标记就取 sortOrder 最小的。 */
    fun selectPrimary(record: SourceRecord?): SourceEndpoint? {
        val endpoints = record?.endpoints ?: return null
        if (endpoints.isEmpty()) return null
        var any: SourceEndpoint? = null
        var primary: SourceEndpoint? = null
        for (endpoint in endpoints) {
            if (any == null || endpoint.sortOrder < any.sortOrder) any = endpoint
            if (endpoint.isPrimary && (primary == null || endpoint.sortOrder < primary.sortOrder)) {
                primary = endpoint
            }
        }
        return primary ?: any
    }

    /** 一条来源里除主端点之外需要上报的端点；只有 0 或 1 个端点时返回空表。 */
    fun notImported(record: SourceRecord?): List<SourceSyncNotImportedEndpoint> {
        val endpoints = record?.endpoints ?: return emptyList()
        if (endpoints.size <= 1) return emptyList()
        val primary = selectPrimary(record)
        val items = ArrayList<SourceSyncNotImportedEndpoint>()
        var skippedPrimary = false
        for (endpoint in endpoints) {
            // 跳过「第一个与主端点引用相同」的那条；重复的同一行仍然要报，不能按值去重。
            if (!skippedPrimary && primary != null && endpoint === primary) {
                skippedPrimary = true
                continue
            }
            items.add(describe(record, endpoint, primary))
        }
        return items
    }

    /** 整封汇总，按信封里 sources 的顺序。 */
    fun notImported(envelope: SourceSyncEnvelope?): List<SourceSyncNotImportedEndpoint> {
        val records = envelope?.sources ?: return emptyList()
        val items = ArrayList<SourceSyncNotImportedEndpoint>()
        for (record in records) {
            items.addAll(notImported(record))
        }
        return items
    }

    /** 整封统计：0 端点只计数不产条目，多端点才产条目。 */
    fun summarize(envelope: SourceSyncEnvelope?): SourceSyncEndpointSummary {
        val records = envelope?.sources ?: return SourceSyncEndpointSummary()
        val items = ArrayList<SourceSyncNotImportedEndpoint>()
        var withAlternatives = 0
        var withoutEndpoint = 0
        for (record in records) {
            when {
                record.endpoints.isEmpty() -> withoutEndpoint += 1
                record.endpoints.size > 1 -> {
                    withAlternatives += 1
                    items.addAll(notImported(record))
                }
            }
        }
        return SourceSyncEndpointSummary(
            items = items,
            recordsWithAlternatives = withAlternatives,
            recordsWithoutEndpoint = withoutEndpoint,
        )
    }

    private fun describe(
        record: SourceRecord,
        endpoint: SourceEndpoint,
        primary: SourceEndpoint?,
    ): SourceSyncNotImportedEndpoint = SourceSyncNotImportedEndpoint(
        syncSourceId = WireText.trim(record.syncSourceId),
        displayName = record.displayName,
        url = endpoint.url,
        normalizedUrl = SyncUrlNormalizer.normalize(endpoint.url),
        sortOrder = endpoint.sortOrder,
        isPrimary = primary != null && endpoint === primary,
        enabled = endpoint.enabled,
        reason = SourceSyncEndpointPolicyReasons.SECONDARY_NOT_IMPORTED,
    )
}
