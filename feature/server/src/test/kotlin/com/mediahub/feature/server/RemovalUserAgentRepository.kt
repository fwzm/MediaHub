package com.mediahub.feature.server

import com.mediahub.core.database.repository.ServerUserAgentRepository

/** In-memory UA preferences for the real removal-chain tests. */
internal class RemovalUserAgentRepository : ServerUserAgentRepository {
    private val values = mutableMapOf<String, String>()
    override val loaded = true
    override fun snapshot(): Map<String, String> = values.toMap()
    override fun snapshotBlocking(timeoutMs: Long): Map<String, String> = snapshot()
    override suspend fun awaitLoaded(timeoutMs: Long) = true
    override suspend fun set(serverId: String, userAgent: String?) {
        if (userAgent.isNullOrBlank()) values.remove(serverId) else values[serverId] = userAgent
    }
    override suspend fun purgeOrphans(existingServerIds: Set<String>): Int {
        val orphanIds = values.keys - existingServerIds
        orphanIds.forEach(values::remove)
        return orphanIds.size
    }
    override suspend fun purge(serverId: String) {
        values.remove(serverId)
    }
}
