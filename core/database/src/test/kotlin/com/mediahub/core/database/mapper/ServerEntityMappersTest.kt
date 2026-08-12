package com.mediahub.core.database.mapper

import com.mediahub.core.database.entity.ServerEntity
import com.mediahub.core.database.mapper.ServerEntityMappers.toDomain
import com.mediahub.core.database.mapper.ServerEntityMappers.toEntity
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEntityMappersTest {

    private val server = MediaServer(
        id = "srv1",
        name = "家庭NAS",
        type = ServerType.WEBDAV,
        baseUrl = "http://192.168.1.10:5005",
        username = "alice",
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
            baseUrl = "", createdAtEpochMs = 0,
        )
        val domain = entity.toDomain()
        assertEquals(ServerType.LOCAL, domain.type)
    }

    @Test
    fun `nullable fields survive round trip`() {
        val minimal = MediaServer(
            id = "m", name = "m", type = ServerType.LOCAL,
            baseUrl = "", createdAtEpochMs = 1,
        )
        val restored = minimal.toEntity().toDomain()
        assertTrue(restored.username == null)
        assertTrue(restored.lastConnectedAtEpochMs == null)
    }
}
