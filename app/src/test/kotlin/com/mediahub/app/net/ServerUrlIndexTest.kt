package com.mediahub.app.net

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M0-A3 回归：UA 归属必须覆盖**所有服务器类型**。
 *
 * 真机缺陷：WebDAV 媒体源没有图片鉴权作用域 → serverId 解析为 null → 自定义 UA 不生效。
 */
class ServerUrlIndexTest {

    private val servers = MutableStateFlow<List<MediaServer>>(emptyList())

    private fun index(): ServerUrlIndex {
        val store = object : ServerStore {
            override fun observeServers() = servers
            override suspend fun getServer(id: String) = servers.value.firstOrNull { it.id == id }
        }
        return ServerUrlIndex(store)
    }

    /** flow 是权威来源：先种 flow（覆盖收集器发射），再同步驱动一次，避免竞态。 */
    private fun apply(idx: ServerUrlIndex, list: List<MediaServer>) {
        servers.value = list
        idx.onServersChanged(list)
    }

    private fun server(id: String, type: ServerType, urls: List<String>) = MediaServer(
        id = id,
        name = id,
        type = type,
        createdAtEpochMs = 0L,
        endpoints = urls.mapIndexed { i, u -> ServerEndpoint(id = "$id-$i", serverId = id, url = u, name = "e$i") },
    )

    @Test
    fun `webdav server resolves without any image auth scope`() {
        val idx = index()
        apply(idx, listOf(server("srv-webdav", ServerType.WEBDAV, listOf("http://127.0.0.1:33780/dav"))))

        assertEquals("srv-webdav", idx.serverIdFor("http://127.0.0.1:33780/dav/".toHttpUrl()))
        assertEquals("srv-webdav", idx.serverIdFor("http://127.0.0.1:33780/dav/UnifiedAV.mkv".toHttpUrl()))
    }

    @Test
    fun `local and emby servers are indexed too`() {
        val idx = index()
        apply(
            idx,
            listOf(
                server("srv-emby", ServerType.EMBY, listOf("https://emby.example")),
                server("srv-local", ServerType.LOCAL, listOf("file:///sdcard/media")),
            ),
        )

        assertEquals("srv-emby", idx.serverIdFor("https://emby.example/Items/1".toHttpUrl()))
        // LOCAL 的 file:// 端点不参与 HTTP 归属（OkHttp HttpUrl 仅接受 http/https），
        // 因此不会误吞其它服务器的请求。
        assertNull(idx.serverIdFor("https://other.example/Items/1".toHttpUrl()))
    }

    @Test
    fun `longest path prefix wins`() {
        val idx = index()
        apply(
            idx,
            listOf(
                server("srv-root", ServerType.WEBDAV, listOf("https://nas.example")),
                server("srv-nested", ServerType.WEBDAV, listOf("https://nas.example/dav/movies")),
            ),
        )

        assertEquals("srv-nested", idx.serverIdFor("https://nas.example/dav/movies/a.mkv".toHttpUrl()))
        assertEquals("srv-root", idx.serverIdFor("https://nas.example/other/a.mkv".toHttpUrl()))
    }

    @Test
    fun `ambiguous same-prefix servers fail closed`() {
        val idx = index()
        apply(
            idx,
            listOf(
                server("srv-a", ServerType.WEBDAV, listOf("https://nas.example/dav")),
                server("srv-b", ServerType.WEBDAV, listOf("https://nas.example/dav")),
            ),
        )

        assertNull(idx.serverIdFor("https://nas.example/dav/a.mkv".toHttpUrl()))
    }

    @Test
    fun `different port or foreign host does not match`() {
        val idx = index()
        apply(idx, listOf(server("srv-a", ServerType.WEBDAV, listOf("http://127.0.0.1:33780/dav"))))

        assertNull(idx.serverIdFor("http://127.0.0.1:33781/dav/a.mkv".toHttpUrl()))
        assertNull(idx.serverIdFor("http://127.0.0.2:33780/dav/a.mkv".toHttpUrl()))
        assertNull(idx.serverIdFor("http://127.0.0.1:33780/other/a.mkv".toHttpUrl()))
    }
}
