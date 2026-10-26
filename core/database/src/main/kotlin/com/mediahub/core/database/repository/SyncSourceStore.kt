package com.mediahub.core.database.repository

import com.mediahub.core.common.sync.LocalSourceRecord

/**
 * 跨设备来源同步用的**只读**视图（W6-B）。
 *
 * 继承 [ServerStore]，所以 "本机服务器" 仍只有一个来源；但它额外暴露同步身份，
 * 而且**故意不提供任何写方法**：预演服务（SourceSyncPreviewService）只依赖这个接口，
 * 编译期就拿不到写路径，"预演零写入" 不是靠自觉而是靠类型。
 *
 * 写身份由 [ServerRepository] 的 bindSyncSourceId / updateSyncRecordRevision 负责，
 * 只在将来的"应用变更"步骤里调用（本轮不做）。
 */
interface SyncSourceStore : ServerStore {

    /** 本机全部来源的规划视图。地址取当前生效线路（主线路优先），与 UI 看到的 baseUrl 一致。 */
    suspend fun listLocalSources(): List<LocalSourceRecord>

    /** 按持久同步身份精确查一条来源；未同步过的记录返回 null。 */
    suspend fun findBySyncSourceId(syncSourceId: String): LocalSourceRecord?
}
