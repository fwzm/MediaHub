package com.mediahub.core.database.mapper

import com.mediahub.core.database.entity.ServerEndpointEntity
import com.mediahub.core.database.entity.ServerEntity
import com.mediahub.core.database.mapper.ServerEntityMappers.toDomain
import com.mediahub.core.database.mapper.ServerEntityMappers.toEntity
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEntityMappersTest {

    private val server = MediaServer(
        id = "srv1",
        name = "家庭NAS",
        type = ServerType.WEBDAV,
        username = "alice",
        note = "家里的 NAS",
        icon = "nas",
        isDefault = true,
        sortOrder = 2,
        createdAtEpochMs = 12345L,
        lastConnectedAtEpochMs = 67890L,
    )

    @Test
    fun `domain to entity to domain round trip`() {
        val entity = server.toEntity()
        val restored = entity.toDomain()
        assertEquals(server, restored)
    }

    @Test
    fun `unknown type falls back to local without crash`() {
        val entity = ServerEntity(
            id = "x", name = "x", type = "UNKNOWN_FUTURE_TYPE",
            createdAtEpochMs = 0,
        )
        val domain = entity.toDomain()
        assertEquals(ServerType.LOCAL, domain.type)
    }

    @Test
    fun `nullable fields survive round trip`() {
        val minimal = MediaServer(
            id = "m", name = "m", type = ServerType.LOCAL,
            createdAtEpochMs = 1,
        )
        val restored = minimal.toEntity().toDomain()
        assertTrue(restored.username == null)
        assertTrue(restored.lastConnectedAtEpochMs == null)
    }

    @Test
    fun `endpoint round trip`() {
        val endpoint = ServerEndpoint(
            id = "ep1", serverId = "srv1", name = "主线路",
            url = "http://192.168.1.10:8096", isPrimary = true, enabled = true, sortOrder = 0,
            lastLatencyMs = 42L, lastError = null, lastTestedAtEpochMs = 99L,
        )
        assertEquals(endpoint, endpoint.toEntity().toDomain())
    }

    @Test
    fun `endpoint entity to domain preserves fields`() {
        val entity = ServerEndpointEntity(
            id = "ep2", serverId = "srv2", name = "备用线路",
            url = "http://backup:8096", isPrimary = false, enabled = true, sortOrder = 1,
            lastLatencyMs = 120L, lastError = "timeout", lastTestedAtEpochMs = 123L,
        )
        val domain = entity.toDomain()
        assertEquals("ep2", domain.id)
        assertEquals("备用线路", domain.name)
        assertEquals("http://backup:8096", domain.url)
        assertEquals(false, domain.isPrimary)
        assertEquals(120L, domain.lastLatencyMs)
        assertEquals("timeout", domain.lastError)
    }
}
