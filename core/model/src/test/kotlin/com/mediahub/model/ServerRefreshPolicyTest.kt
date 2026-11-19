package com.mediahub.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRefreshPolicyTest {

    private fun endpoint(
        url: String = "https://example.com",
        isPrimary: Boolean = true,
        enabled: Boolean = true,
        sortOrder: Int = 0,
        lastApiLatencyMs: Long? = null,
    ): ServerEndpoint = ServerEndpoint(
        id = "ep-$sortOrder",
        serverId = "srv",
        name = "Line $sortOrder",
        url = url,
        isPrimary = isPrimary,
        enabled = enabled,
        sortOrder = sortOrder,
        lastApiLatencyMs = lastApiLatencyMs,
    )

    private fun server(
        type: ServerType = ServerType.EMBY,
        name: String = "Test",
        username: String? = null,
        note: String? = null,
        icon: String? = null,
        endpoints: List<ServerEndpoint> = listOf(endpoint()),
    ): MediaServer = MediaServer(
        id = "srv",
        name = name,
        type = type,
        username = username,
        note = note,
        icon = icon,
        createdAtEpochMs = 0L,
        endpoints = endpoints,
    )

    @Test
    fun endpointUrlChangeRequiresReload() {
        assertTrue(
            ServerRefreshPolicy.requiresReload(
                server(),
                server(endpoints = listOf(endpoint(url = "https://new.example.com"))),
            ),
        )
    }

    @Test
    fun displayMetadataChangeDoesNotRequireReload() {
        assertFalse(
            ServerRefreshPolicy.requiresReload(
                server(),
                server(name = "Renamed", note = "new note", icon = "builtin://emby"),
            ),
        )
    }

    @Test
    fun endpointQualityChangeDoesNotRequireReload() {
        assertFalse(
            ServerRefreshPolicy.requiresReload(
                server(),
                server(endpoints = listOf(endpoint(lastApiLatencyMs = 42L))),
            ),
        )
    }

    @Test
    fun usernameChangeRequiresReload() {
        assertTrue(
            ServerRefreshPolicy.requiresReload(
                server(username = "alice"),
                server(username = "bob"),
            ),
        )
    }

    @Test
    fun serverTypeChangeRequiresReload() {
        assertTrue(
            ServerRefreshPolicy.requiresReload(
                server(type = ServerType.EMBY),
                server(type = ServerType.JELLYFIN),
            ),
        )
    }

    @Test
    fun enablingBackupEndpointRequiresReload() {
        val backup = endpoint(url = "https://backup.example.com", isPrimary = false, enabled = false, sortOrder = 1)
        assertTrue(
            ServerRefreshPolicy.requiresReload(
                server(endpoints = listOf(endpoint(), backup)),
                server(endpoints = listOf(endpoint(), backup.copy(enabled = true))),
            ),
        )
    }
}
