package com.mediahub.app.image

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderImageAuthContributor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        /** true = 模拟 Emby 反代兼容：baseUrl 带非空 path 时额外登记 baseUrl 自身。 */
        private val addBaseScopeForSubPath: Boolean = false,
    ) : ProviderImageAuthContributor {
        override fun authScopeUrl(baseUrl: String): String =
            if (scopeSuffix == null) baseUrl.trimEnd('/') else baseUrl.trimEnd('/') + scopeSuffix

        override fun authScopeUrls(baseUrl: String): List<String> {
            val scope = authScopeUrl(baseUrl)
            if (!addBaseScopeForSubPath) return listOf(scope)
            val path = baseUrl.trim().toHttpUrlOrNull()?.encodedPath.orEmpty().trim('/')
            return if (path.isEmpty()) listOf(scope) else listOf(scope, baseUrl.trimEnd('/')).distinct()
        }

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

    @Test
    fun `server without image auth contributor still resolves its id for request policy`() {
        val store = store(
            server("srv-webdav", ServerType.WEBDAV, "http://127.0.0.1:33780/dav"),
        )

        assertEquals("srv-webdav", store.serverIdForUrl(url("http://127.0.0.1:33780/dav/")))
        assertNull("无图片鉴权 contributor 的服务器不得伪造认证头", store.headersForUrl(
            url("http://127.0.0.1:33780/dav/"),
        ))
    }

    // ---- 场景 6（用户条目 22 / 审计 149）：反代子路径 baseUrl 的图片绝对 URL 归属 ----

    private fun subPathEmby() = FakeContributor(
        ServerType.EMBY, "/emby", "X-Emby-Token", addBaseScopeForSubPath = true,
    )

    private fun storeWith(contributors: Set<ProviderImageAuthContributor>, vararg servers: MediaServer) =
        ProviderImageAuthStore(
            serverStore = FakeServerStore(servers.toList()),
            contributors = contributors,
        ).also { it.onServersChanged(servers.toList()) }

    @Test
    fun `reverse proxy sub path base url also authenticates absolute item image urls`() {
        val store = storeWith(
            setOf(subPathEmby()),
            server("srv-emby", ServerType.EMBY, "https://h.example.com/custom"),
        )

        // 服务端返回的图片绝对 URL 不含 /emby 前缀：旧实现匹配不到任何 scope → 401 空白。
        assertEquals(
            "srv-emby",
            store.headersForUrl(url("https://h.example.com/custom/Items/1/Images/Primary"))!!["X-Emby-Token"],
        )
        assertEquals(
            "srv-emby",
            store.headersForUrl(url("https://h.example.com/custom/emby/Items/1/Images/Primary"))!!["X-Emby-Token"],
        )
    }

    @Test
    fun `reverse proxy sub path scope never leaks credentials to another origin`() {
        val store = storeWith(
            setOf(subPathEmby()),
            server("srv-emby", ServerType.EMBY, "https://h.example.com/custom"),
        )

        assertNull(
            "跨 origin 的图片 URL 必须无鉴权头",
            store.headersForUrl(url("https://evil.example/custom/Items/1/Images/Primary")),
        )
        assertNull(store.serverIdForUrl(url("https://evil.example/custom/Items/1/Images/Primary")))
    }

    @Test
    fun `reverse proxy sub path scopes do not bleed into sibling servers on the same origin`() {
        val store = storeWith(
            setOf(subPathEmby(), jellyfinLike),
            server("srv-emby", ServerType.EMBY, "https://h.example.com/custom"),
            server("srv-jf", ServerType.JELLYFIN, "https://h.example.com/custom/jellyfin"),
        )

        assertEquals(
            "srv-emby",
            store.headersForUrl(url("https://h.example.com/custom/emby/Items/1/Images/Primary"))!!["X-Emby-Token"],
        )
        assertEquals(
            "srv-jf",
            store.headersForUrl(url("https://h.example.com/custom/jellyfin/Items/1/Images/Primary"))!!["Authorization"],
        )
        assertNull(
            "同 origin 无关路径不得归属任何服务器",
            store.headersForUrl(url("https://h.example.com/other/Items/1/Images/Primary")),
        )
    }

    @Test
    fun `identical sub path base url for two servers fails closed instead of sharing tokens`() {
        val store = storeWith(
            setOf(subPathEmby(), jellyfinLike),
            server("srv-emby", ServerType.EMBY, "https://h.example.com/custom"),
            server("srv-jf", ServerType.JELLYFIN, "https://h.example.com/custom"),
        )

        assertNull(
            "同一 path 前缀归属两台服务器时必须 fail closed",
            store.headersForUrl(url("https://h.example.com/custom/Items/1/Images/Primary")),
        )
        assertEquals(
            "协议前缀 scope 仍可唯一归属 Emby",
            "srv-emby",
            store.headersForUrl(url("https://h.example.com/custom/emby/Items/1/Images/Primary"))!!["X-Emby-Token"],
        )
    }

    @Test
    fun `bare origin base url does not claim the root path scope`() {
        val store = storeWith(
            setOf(subPathEmby()),
            server("srv-emby", ServerType.EMBY, "https://h.example.com"),
        )

        assertNull(
            "裸 origin 不得生成根路径 scope（否则吞并同 origin 其它服务器）",
            store.headersForUrl(url("https://h.example.com/Items/1/Images/Primary")),
        )
        assertEquals(
            "srv-emby",
            store.headersForUrl(url("https://h.example.com/emby/Items/1/Images/Primary"))!!["X-Emby-Token"],
        )
    }
}
