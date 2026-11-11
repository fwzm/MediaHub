package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.AndroidSourceTypeMap
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.database.entity.ServerEntity
import com.mediahub.model.ServerType

/**
 * 同步身份三列的可写投影（LocalSourceRecord -> servers 表）。
 *
 * 单独建模而不是直接改 MediaServer：领域模型是 UI 的契约，同步身份属于存储/同步层，
 * 不应该因此扩散到每一个 ViewModel 与 fake（见 docs/android-source-sync.md「存储接线」）。
 */
data class ServerSyncIdentity(
    val syncSourceId: String?,
    val recordRevision: Long,
    val sourceTypeRaw: String?,
)

/**
 * servers 表 <-> 同步规划视图（[LocalSourceRecord]）的双向映射。
 *
 * wire 类型只经 [AndroidSourceTypeMap] 解析，不在这里做名字推导；解析不出来的值
 * （例如将来新增的云盘协议）保留在 sync_source_type_raw 列里，type 记为 null 而不是
 * 降级成某个枚举值 —— 降级会让下一次同步把原本的 wire 值永久覆盖掉。
 */
object ServerSyncMappers {

    /**
     * @param primaryUrl 当前生效线路地址（由仓库层用 activeEndpoint() 取，DB 层不猜）。
     */
    fun ServerEntity.toLocalSourceRecord(primaryUrl: String? = null): LocalSourceRecord = LocalSourceRecord(
        serverId = id,
        syncSourceId = syncSourceId?.trim()?.takeIf { it.isNotEmpty() },
        type = sourceType(),
        url = primaryUrl?.takeIf { it.isNotBlank() },
        accountScope = username?.trim()?.takeIf { it.isNotEmpty() },
        displayName = name,
        recordRevision = syncRecordRevision,
        sourceTypeRaw = syncSourceTypeRaw?.takeIf { it.isNotBlank() },
    )

    /**
     * 本端类型：优先按保存下来的原始 wire 值解析（未知值得到 null），
     * 没有原始值时回退到 type 列（旧记录只有这一列）。
     */
    fun ServerEntity.sourceType(): ServerType? {
        val raw = syncSourceTypeRaw?.takeIf { it.isNotBlank() }
        if (raw != null) return AndroidSourceTypeMap.toServerType(raw)
        return runCatching { ServerType.valueOf(type) }.getOrNull()
    }

    /**
     * 规划视图 -> 可写身份列。sourceTypeRaw 缺失时用 type 反推 wire（已知类型才写得出，
     * 未知类型本来就没有枚举，只能靠调用方把原始值传进来）。
     */
    fun LocalSourceRecord.toSyncIdentity(): ServerSyncIdentity = ServerSyncIdentity(
        syncSourceId = syncSourceId?.trim()?.takeIf { it.isNotEmpty() },
        recordRevision = recordRevision,
        sourceTypeRaw = sourceTypeRaw?.takeIf { it.isNotBlank() } ?: AndroidSourceTypeMap.toWire(type),
    )

    /** 只写同步身份三列，绝不碰 name/type/username 等用户可见字段。 */
    fun ServerEntity.applySyncIdentity(identity: ServerSyncIdentity): ServerEntity = copy(
        syncSourceId = identity.syncSourceId,
        syncRecordRevision = identity.recordRevision,
        syncSourceTypeRaw = identity.sourceTypeRaw,
    )
}
