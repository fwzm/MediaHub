package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.api.EmbyImageType
import com.mediahub.provider.emby.mapper.EmbyImageMapper
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Emby 图片 URL 契约与类型策略（Phase 1B-2.3）：
 * URL 不含 Token（ADR-026）；Episode 优先 Thumb；无 ImageTags 不生成 URL。
 */
class EmbyImageMapperTest {

    private lateinit var server: MockWebServer
    private lateinit var api: EmbyApiClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val logger = StdoutLogger()
        api = EmbyApiClient(
            endpointResolver = EmbyEndpointResolver(server.url("/").toString().trimEnd('/')),
            apiClient = ApiClient(OkHttpClient(), logger = logger),
            authHeaderBuilder = EmbyAuthorizationHeaderBuilder(
                ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0"),
            ),
            logger = logger,
        )
    }

    @After
    fun tearDown() { server.shutdown() }

    private fun item(type: MediaType) = MediaItem(serverId = "srv-1", id = "item-1", type = type, title = "T")

    // ---- URL 契约 ----
    @Test
    fun `image url path and query contract without credentials`() {
        val url = api.imageUrl("item-1", EmbyImageType.PRIMARY, "tag-abc", 400)
        val httpUrl = url.toHttpUrl()
        assertEquals("/emby/Items/item-1/Images/Primary", httpUrl.encodedPath)
        assertEquals("tag-abc", httpUrl.queryParameter("tag"))
        assertEquals("400", httpUrl.queryParameter("maxWidth"))
        assertEquals("85", httpUrl.queryParameter("quality"))
        // 红线：任何凭据形状都不得出现在 URL
        assertFalse(url.contains("X-Emby-Token", ignoreCase = true))
        listOf("token", "api_key", "api-key", "access_token").forEach {
            assertNull("URL must not contain '$it'", httpUrl.queryParameter(it))
        }
    }

    @Test
    fun `image url omits tag query when tag is null`() {
        val url = api.imageUrl("item-1", EmbyImageType.BACKDROP, null, 1280)
        val httpUrl = url.toHttpUrl()
        assertNull(httpUrl.queryParameter("tag"))
        assertEquals("/emby/Items/item-1/Images/Backdrop", httpUrl.encodedPath)
    }

    // ---- 类型策略 ----
    @Test
    fun `movie gets primary poster and first backdrop with different widths`() {
        val enriched = EmbyImageMapper.enrich(
            item(MediaType.MOVIE), api,
            imageTags = mapOf("Primary" to "p1"),
            backdropTags = listOf("b1", "b2"),
        )
        assertTrue(enriched.posterUrl!!.endsWith("/emby/Items/item-1/Images/Primary?tag=p1&maxWidth=400&quality=85"))
        assertTrue(enriched.backdropUrl!!.endsWith("/emby/Items/item-1/Images/Backdrop?tag=b1&maxWidth=1280&quality=85"))
    }

    @Test
    fun `episode prefers thumb over primary and has no backdrop`() {
        val enriched = EmbyImageMapper.enrich(
            item(MediaType.EPISODE), api,
            imageTags = mapOf("Primary" to "p1", "Thumb" to "t1"),
            backdropTags = listOf("b1"),
        )
        assertTrue(enriched.posterUrl!!.endsWith("/emby/Items/item-1/Images/Thumb?tag=t1&maxWidth=400&quality=85"))
        assertNull(enriched.backdropUrl)
    }

    @Test
    fun `episode falls back to primary when no thumb`() {
        val enriched = EmbyImageMapper.enrich(
            item(MediaType.EPISODE), api,
            imageTags = mapOf("Primary" to "p1"),
            backdropTags = emptyList(),
        )
        assertTrue(enriched.posterUrl!!.endsWith("/emby/Items/item-1/Images/Primary?tag=p1&maxWidth=400&quality=85"))
    }

    @Test
    fun `no image tags yields null urls`() {
        val enriched = EmbyImageMapper.enrich(item(MediaType.MOVIE), api, imageTags = null, backdropTags = emptyList())
        assertNull(enriched.posterUrl)
        assertNull(enriched.backdropUrl)
    }

    @Test
    fun `folder and audio are not enriched`() {
        val folder = EmbyImageMapper.enrich(
            item(MediaType.FOLDER), api,
            imageTags = mapOf("Primary" to "p1"), backdropTags = listOf("b1"),
        )
        assertNull(folder.posterUrl)
        assertNull(folder.backdropUrl)
    }
}
