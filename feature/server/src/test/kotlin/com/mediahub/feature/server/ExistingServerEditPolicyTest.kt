package com.mediahub.feature.server

import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Existing Server Re-login 编辑策略（评审 FINAL PATCH 2）。
 * 覆盖：descriptor 匹配、prefill、same id + 元数据保留、Provider 锁定、新建不受破坏。
 */
class ExistingServerEditPolicyTest {

    private val embyDescriptor = ProviderDescriptor(
        id = "emby",
        serverType = ServerType.EMBY,
        displayName = "Emby",
        category = ProviderCategory.MEDIA_SERVER,
        declaredCapabilities = setOf(ProviderCapability.AUTH),
        authMethod = AuthMethod.USERNAME_PASSWORD,
        status = ProviderStatus.STABLE,
    )
    private val webdavDescriptor = ProviderDescriptor(
        id = "webdav",
        serverType = ServerType.WEBDAV,
        displayName = "WebDAV",
        category = ProviderCategory.CLOUD_STORAGE,
        declaredCapabilities = setOf(ProviderCapability.AUTH),
        authMethod = AuthMethod.BASIC,
        status = ProviderStatus.STABLE,
    )

    private fun embyServer(id: String = "srv-1") = MediaServer(
        id = id,
        name = "我的Emby",
        type = ServerType.EMBY,
        baseUrl = "http://192.168.1.10:8096",
        username = "alice",
        isDefault = true,
        sortOrder = 3,
        createdAtEpochMs = 100L,
        lastConnectedAtEpochMs = 200L,
        lastError = "旧错误",
    )

    // ---- descriptor 匹配：必须用 serverType，禁 "EMBY" != "emby" ----

    @Test
    fun `descriptor matched by serverType not enum name`() {
        val descriptor = ExistingServerEditPolicy.descriptorFor(embyServer(), listOf(embyDescriptor, webdavDescriptor))
        // 关键：返回 embyDescriptor（id = "emby"），而非 ServerType.EMBY.name（"EMBY"）
        assertEquals("emby", descriptor?.id)
        assertEquals(ServerType.EMBY, descriptor?.serverType)
    }

    @Test
    fun `descriptor null when no match`() {
        val unknown = MediaServer(id = "x", name = "x", type = ServerType.ALIYUN_DRIVE, baseUrl = "", createdAtEpochMs = 0)
        assertNull(ExistingServerEditPolicy.descriptorFor(unknown, listOf(embyDescriptor)))
    }

    // ---- prefill / same id / metadata 保留 ----

    @Test
    fun `existing draft reuses same id and preserves full metadata`() {
        val candidate = MediaServer(
            id = "brand-new-different-id",
            name = "新名称",
            type = ServerType.EMBY, // 用户只改了 name/baseUrl/username
            baseUrl = "http://new-host:8096",
            username = "bob",
            createdAtEpochMs = 999L,
        )
        val draft = ExistingServerEditPolicy.buildDraft(existing = embyServer(), candidate)

        assertTrue(draft.updateSource)          // 走 updateServer
        assertTrue(draft.providerLocked)        // Provider 锁定
        assertEquals("srv-1", draft.server.id)  // 复用 SAME id
        assertEquals("新名称", draft.server.name)
        assertEquals(ServerType.EMBY, draft.server.type)
        assertTrue(draft.server.isDefault)      // 保留
        assertEquals(3, draft.server.sortOrder) // 保留
        assertEquals(100L, draft.server.createdAtEpochMs) // 保留
        assertEquals(200L, draft.server.lastConnectedAtEpochMs) // 保留
        assertEquals("旧错误", draft.server.lastError)         // 保留
        // 绝不产生第二个 id
        assertFalse(draft.server.id == "brand-new-different-id")
    }

    @Test
    fun `existing draft locks provider type`() {
        val candidateTryingWebDav = MediaServer(
            id = "x", name = "x", type = ServerType.WEBDAV,
            baseUrl = "http://x", createdAtEpochMs = 0,
        )
        val draft = ExistingServerEditPolicy.buildDraft(existing = embyServer(), candidateTryingWebDav)
        assertEquals(ServerType.EMBY, draft.server.type) // 锁定原 type
    }

    @Test
    fun `new server goes to addSource and no metadata inheritance`() {
        val nb = MediaServer(
            id = "srv-new", name = "新", type = ServerType.EMBY,
            baseUrl = "http://x", createdAtEpochMs = 1L,
        )
        val draft = ExistingServerEditPolicy.buildDraft(existing = null, nb)
        assertFalse(draft.updateSource)          // 走 addServer
        assertFalse(draft.providerLocked)
        assertEquals("srv-new", draft.server.id)
    }

    // ---- needsRelogin 精确语义 ----

    @Test
    fun `needsRelogin only for signed out or session expired or mismatch`() {
        val server = embyServer()
        val state = { kind: AuthSessionErrorKind ->
            AuthSessionState.Error(kind, "x")
        }
        assertTrue(ExistingServerEditPolicy.needsRelogin(server, AuthSessionState.SignedOut, true))
        assertTrue(ExistingServerEditPolicy.needsRelogin(server, state(AuthSessionErrorKind.SESSION_EXPIRED), true))
        assertTrue(ExistingServerEditPolicy.needsRelogin(server, state(AuthSessionErrorKind.SERVER_MISMATCH), true))
    }

    @Test
    fun `needsRelogin false for transient and authenticated`() {
        val server = embyServer()
        val state = { kind: AuthSessionErrorKind ->
            AuthSessionState.Error(kind, "x")
        }
        assertFalse(ExistingServerEditPolicy.needsRelogin(server, AuthSessionState.Authenticated(com.mediahub.model.MediaUser("s1", "u1", "a")), true))
        assertFalse(ExistingServerEditPolicy.needsRelogin(server, AuthSessionState.Unknown, true))
        assertFalse(ExistingServerEditPolicy.needsRelogin(server, AuthSessionState.Restoring, true))
        assertFalse(ExistingServerEditPolicy.needsRelogin(server, state(AuthSessionErrorKind.FORBIDDEN), true))
        assertFalse(ExistingServerEditPolicy.needsRelogin(server, state(AuthSessionErrorKind.NETWORK_TIMEOUT), true))
        assertFalse(ExistingServerEditPolicy.needsRelogin(server, state(AuthSessionErrorKind.NETWORK_UNAVAILABLE), true))
        assertFalse(ExistingServerEditPolicy.needsRelogin(server, state(AuthSessionErrorKind.SERVER_ERROR), true))
        assertFalse(ExistingServerEditPolicy.needsRelogin(server, state(AuthSessionErrorKind.INVALID_RESPONSE), true))
        assertFalse(ExistingServerEditPolicy.needsRelogin(server, state(AuthSessionErrorKind.UNKNOWN), true))
    }

    @Test
    fun `needsRelogin false for non-auth provider`() {
        assertFalse(ExistingServerEditPolicy.needsRelogin(embyServer(), AuthSessionState.SignedOut, false))
    }
}
