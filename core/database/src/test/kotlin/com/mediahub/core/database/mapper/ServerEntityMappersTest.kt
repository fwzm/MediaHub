package com.mediahub.core.database.mapper

import com.mediahub.core.database.entity.ServerEntity
import com.mediahub.core.database.mapper.ServerEntityMappers.toDomain
import com.mediahub.core.database.mapper.ServerEntityMappers.toEntity
import com.mediahub.model.MediaServer
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerEntityMappersTest {
    @Test
    fun `legacy enum value maps to stable provider id`() {
        val entity = entity(type = "ALIYUN_DRIVE")
        assertEquals("aliyundrive", entity.toDomain().providerId)
    }

    @Test
    fun `open provider id survives domain round trip`() {
        val server = MediaServer(
            id = "server-1",
            name = "Custom",
            providerId = "custom-sftp",
            baseUrl = "sftp://example.invalid",
            createdAtEpochMs = 1L,
        )

        assertEquals("custom-sftp", server.toEntity().toDomain().providerId)
    }

    private fun entity(type: String) = ServerEntity(
        id = "server-1",
        name = "Legacy",
        type = type,
        baseUrl = "https://example.invalid",
        createdAtEpochMs = 1L,
    )
}
