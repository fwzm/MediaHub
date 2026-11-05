package com.mediahub.core.common.sync

import kotlinx.serialization.json.JsonElement

/**
 * 同步信封（ADR-S001 D2）在 Android 侧的等价物。
 *
 * 字段名与 Windows SourceSyncEnvelope / SourceRecord / SourceEndpoint / Tombstone 一一对应，
 * 线格式由 SourceSyncCodec 负责，本类只承载语义。所有集合默认空、可选值默认 null 或空串，
 * 与 Windows 的默认值一致。
 */
data class SourceSyncEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val minimumReaderVersion: Int = 1,
    val deviceId: String = "",
    val snapshotId: String = "",
    val createdAtEpochMs: Long = 0L,
    val capabilities: SyncCapabilities = SyncCapabilities(),
    val sources: List<SourceRecord> = emptyList(),
    val deletions: List<SourceTombstone> = emptyList(),
) {
    companion object {
        /** 本实现能读能写的唯一 schema 版本（与 Windows SourceSyncEnvelope.CurrentSchemaVersion 对齐）。 */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/** 发送端能力声明；sourceTypes 里可以出现本端不认识的值，读端不得丢弃。 */
data class SyncCapabilities(
    val canSendSecrets: Boolean = false,
    val sourceTypes: List<String> = emptyList(),
)

/** 一个来源记录。 */
data class SourceRecord(
    val syncSourceId: String = "",
    val sourceType: SyncSourceTypeValue = SyncSourceTypeValue.parse(null),
    val displayName: String = "",
    val accountScope: String? = null,
    val endpoints: List<SourceEndpoint> = emptyList(),
    val recordRevision: Long = 0L,
    val fieldVersion: Int = 0,
    /**
     * 不透明片段：逐字复制、不解释、不校验、不记录日志（D6）。
     * 本端导入后必须按 Android 的 SecretStorage 重新封装，而不是直接使用这里的字节。
     */
    val secrets: JsonElement? = null,
)

/** 一条端点地址；sortOrder 只用于"谁是主地址"的稳定排序。 */
data class SourceEndpoint(
    val url: String = "",
    val isPrimary: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)

/** 墓碑：远端已删除某来源。对应 Windows Tombstone（类名加前缀以示归属，字段逐一相同）。 */
data class SourceTombstone(
    val syncSourceId: String = "",
    val deletedAtEpochMs: Long = 0L,
    val recordRevision: Long = 0L,
)
