package com.mediahub.core.database.repository

import com.mediahub.model.MediaServer
import kotlinx.coroutines.flow.Flow

/**
 * 服务器存取接口（可测性抽象，FINAL PATCH 4；Phase 1I 扩展编辑/质量写路径）。
 * [ServerRepository] 是其唯一生产实现；测试可用内存 fake。
 *
 * 写路径成员带抛错默认实现：既有只读 fake（home/library/search/detail/player 等）
 * 无需逐一修改即可编译；若某测试实际触达写路径而未重写，会在此大声失败而非静默吞掉。
 */
interface ServerStore {
    fun observeServers(): Flow<List<MediaServer>>
    suspend fun getServer(id: String): MediaServer?

    /** 编辑器保存（名称/备注/图标/主线路 URL）。 */
    suspend fun updateServer(server: MediaServer): Unit =
        throw UnsupportedOperationException("ServerStore fake 未实现 updateServer")

    /** 设为默认媒体源（原子清旧设新，保证最多一个 isDefault）。 */
    suspend fun setDefault(id: String): Unit =
        throw UnsupportedOperationException("ServerStore fake 未实现 setDefault")

    /**
     * 线路质量结果持久化（U4-D）。Phase 1I review P2：
     * 仅当 `[endpointId]` 对应线路的当前 URL 仍等于 `[expectedUrl]` 时写入
     * （校验与写入在同一事务内，防检查-写入竞态）；身份或地址已变化则跳过，
     * 不得重挑当前主线路承接旧测量。调用方须先完成请求身份与草稿版本校验。
     */
    suspend fun updateEndpointQuality(
        serverId: String,
        endpointId: String,
        expectedUrl: String,
        apiLatencyMs: Long?,
        mediaFirstByteMs: Long?,
        throughputMbps: Double?,
        protocol: String?,
        supportsRange: Boolean?,
        httpCode: Int?,
    ): Unit = throw UnsupportedOperationException("ServerStore fake 未实现 updateEndpointQuality")
}
