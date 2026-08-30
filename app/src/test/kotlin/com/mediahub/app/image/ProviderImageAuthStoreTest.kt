package com.mediahub.app.image

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderImageAuthContributor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图片鉴权 scope 归属（ADR-039 review hardening）：
 * 同 origin 多服务器按 auth-scope path 前缀归属，不串凭据；同 scope 多 server fail closed。
 */
class ProviderImageAuthStoreTest {

    private class FakeServerStore(servers: List<MediaServer>) : ServerStore {
        val flow = MutableStateFlow(servers)
        override fun observeServers(): Flow<List<MediaServer>> = flow
        override suspend fun getServer(id: String): MediaServer? = flow.value.firstOrNull { it.id == id }
    }

    /** scopeSuffix=null 表示 scope=base 原样；头值=serverId，用于断言归属。 */
    private class FakeContributor(
        override val serverType: ServerType,
        private val scopeSuffix: String?,
        private val headerName: String,
    ) : ProviderImageAuthContributor {
        override fun authScopeUrl(baseUrl: String): String =
            if (scopeSuffix == null) baseUrl.trimEnd('/') else baseUrl.trimEnd('/') + scopeSuffix

        override suspend fun headersFor(serverId: String): Map<String, String> = mapOf(headerName to serverId)
    }

    private fun server(id: String, type: ServerType, baseUrl: String) = MediaServer(
        id = id, name = id, type = type, baseUrl = baseUrl, createdAtEpochMs = 0,
    )

    private val embyLike = FakeContributor(ServerType.EMBY, "/emby", "X-Emby-Token")
    private val jellyfinLike = FakeContributor(ServerType.JELLYFIN, null, "Authorization")

    private fun store(vararg servers: MediaServer): ProviderImageAuthStore {
        val store = ProviderImageAuthStore(
            serverStore = FakeServerStore(servers.toList()),
            contributors = setOf(embyLike, jellyfinLike),
        )
        store.onServersChanged(servers.toList())
        return store
    }

    private fun url(spec: String): HttpUrl = spec.toHttpUrl()

    // ---- 场景 1：同 origin 反代共存（Emby /emby + Jellyfin /jellyfin）→ 各自归属 ----

    @Test
    fun `same origin reverse proxy subpaths attribute their own credentials`() {
        val store = store(
            server("srv-emby", ServerType.EMBY, "https://media.example.com"),
            server("srv-jf", ServerType.JELLYFIN, "https://media.example.com/jellyfin"),
        )

        val embyHeaders = store.headersForUrl(
            url("https://media.example.com/emby/Items/1/Images/Primary")
        )
        assertEquals("srv-emby", embyHeaders!!["X-Emby-Token"])

        val jfHeaders = store.headersForUrl(
            url("https://media.example.com/jellyfin/Items/1/Images/Primary")
        )
        assertEquals("srv-jf", jfHeaders!!["Authorization"])
    }

    // ---- 场景 2：双 root base 同 origin —— /emby/... 归 Emby，/Items/... 归 Jellyfin ----

    @Test
    fun `root base emby and root base jellyfin split by longest prefix`() {
        val store = store(
            server("srv-emby", ServerType.EMBY, "https://media.example.com"),
            server("srv-jf", ServerType.JELLYFIN, "https://media.example.com"),
        )

        assertEquals(
            "srv-emby",
            store.headersForUrl(url("https://media.example.com/emby/Items/2/Images/Primary"))!!["X-Emby-Token"],
        )
        assertEquals(
            "srv-jf",
            store.headersForUrl(url("https://media.example.com/Items/2/Images/Primary"))!!["Authorization"],
        )
    }

    // ---- 场景 3：同 exact scope 两个 serverId → fail closed（无凭据） ----

    @Test
    fun `identical auth scope with two servers fails closed`() {
        val store = store(
            server("srv-jf-1", ServerType.JELLYFIN, "https://media.example.com"),
            server("srv-jf-2", ServerType.JELLYFIN, "https://media.example.com"),
        )

        assertNull(store.headersForUrl(url("https://media.example.com/Items/1/Images/Primary")))
    }

    // ---- 场景 4：未知 origin / 无匹配 scope → 原样放行 ----

    @Test
    fun `unknown origin or non matching path passes through without credentials`() {
        val store = store(
            server("srv-jf", ServerType.JELLYFIN, "https://media.example.com/jellyfin"),
        )

        assertNull(store.headersForUrl(url("https://other.example.com/jellyfin/Items/1/Images/Primary")))
        assertNull(store.headersForUrl(url("https://media.example.com/other/Items/1/Images/Primary")))
    }

    // ---- 场景 5：path-segment boundary——/embyxyz 不得命中 /emby scope ----

    @Test
    fun `scope matching respects path segment boundaries`() {
        val store = store(
            server("srv-emby", ServerType.EMBY, "https://media.example.com"),
        )

        assertNull(store.headersForUrl(url("https://media.example.com/embyxyz/Items/1/Images/Primary")))
        assertEquals(
            "srv-emby",
            store.headersForUrl(url("https://media.example.com/emby/Items/1/Images/Primary"))!!["X-Emby-Token"],
        )
    }
}
