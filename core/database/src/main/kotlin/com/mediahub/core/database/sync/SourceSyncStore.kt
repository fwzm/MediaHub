package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.database.repository.SyncSourceStore
import com.mediahub.model.ServerType

/**
 * 一条本机来源的完整快照（W6-C）：导入/导出的写入与读取视图。
 *
 * 与 [com.mediahub.core.common.sync.LocalSourceRecord] 的区别：那个是给规划器用的最小视图
 * （只带生效线路地址），这个带**全部线路**、创建时间与服务器 id，够用来落库和拼信封。
 * 不复用 MediaServer 是因为领域模型不携带同步身份三列（见 W6-B 的存储接线说明）。
 */
data class SourceSyncSourceSnapshot(
    val serverId: String = "",
    val name: String = "",
    val serverType: ServerType? = null,
    /** 原始 wire 值（可能本端不认识）。为空表示没有保存过原始值，导出时回退 type 反推。 */
    val sourceTypeRaw: String? = null,
    val accountScope: String? = null,
    val syncSourceId: String? = null,
    val recordRevision: Long = 0L,
    val endpoints: List<SourceEndpoint> = emptyList(),
    val createdAtEpochMs: Long = 0L,
)

/**
 * 导入/导出路径需要的读写口（W6-C）。
 *
 * 与只读的 [SyncSourceStore] 分开：预演服务只拿得到只读接口（编译期零写入），
 * 真正会写库的 [AndroidSourceSyncService] 才依赖本接口。
 *
 * 三个写方法的语义都被刻意限制在"来源身份与展示字段"上：
 * - [createLocalSource] 只能创建一条全新来源（id 由存储层生成）；
 * - [updateLocalSource] 只更新 name / type / username / endpoints + 同步身份三列，
 *   不碰 isDefault / sortOrder / 创建时间（本地偏好不被远端覆盖）；
 * - [bindSyncIdentity] 只写身份三列，用于导出时给尚未编号的来源补上稳定 id。
 * 本接口**没有删除方法**：ADR-S001 里墓碑不参与跨设备删除，本地来源不得被信封删掉，
 * 这一点由类型保证，而不是靠调用方自觉。
 */
interface SourceSyncStore : SyncSourceStore {

    /** 全部本机来源的完整快照，按存储层自然顺序（sortOrder, createdAtEpochMs）。 */
    suspend fun listSourceSnapshots(): List<SourceSyncSourceSnapshot>

    /** 新建来源并写入同步身份；返回新服务器 id。 */
    suspend fun createLocalSource(snapshot: SourceSyncSourceSnapshot): String

    /** 更新已有来源的展示字段、线路与同步身份；返回 false 表示该服务器已不存在。 */
    suspend fun updateLocalSource(serverId: String, snapshot: SourceSyncSourceSnapshot): Boolean

    /** 只写身份三列（导出补号用）；返回 false 表示该服务器已不存在。 */
    suspend fun bindSyncIdentity(
        serverId: String,
        syncSourceId: String,
        recordRevision: Long,
        sourceTypeRaw: String?,
    ): Boolean
}
