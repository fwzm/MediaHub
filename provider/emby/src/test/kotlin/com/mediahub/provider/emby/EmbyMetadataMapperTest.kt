package com.mediahub.provider.emby

import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.ApiClient
import com.mediahub.model.Person
import com.mediahub.provider.emby.api.EmbyApiClient
import com.mediahub.provider.emby.api.EmbyAuthorizationHeaderBuilder
import com.mediahub.provider.emby.api.EmbyEndpointResolver
import com.mediahub.provider.emby.api.EmbyPersonDto
import com.mediahub.provider.emby.api.EmbyStudioDto
import com.mediahub.provider.emby.mapper.EmbyMetadataMapper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 演职人员/制作公司/标签映射测试（Phase 1B-3 Metadata Pipeline）。
 * 覆盖：People 映射、头像 URL 走 personId、空数据安全。
 */
class EmbyMetadataMapperTest {

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
                ClientIdentity("MediaHub", "Android", "dev-1", "0.1.0")
            ),
            logger = logger,
        )
    }

    @After
    fun tearDown() { server.shutdown() }

    // ---- People ----

    @Test
    fun `people 映射含 id name role type 和头像 URL`() = runBlocking {
        val people = listOf(
            EmbyPersonDto(id = "123", name = "Bryan Cranston", role = "Walter White", type = "Actor", primaryImageTag = "abc"),
        )
        val result = EmbyMetadataMapper.mapPeople(api, people)
        assertEquals(1, result.size)
        val person = result[0]
        assertEquals("Bryan Cranston", person.name)
        assertEquals(Person.Role.ACTOR, person.role)
        assertEquals("123", person.id)
        assertEquals("Actor", person.type)
        assertTrue("imageUrl should contain /Items/123/Images/Primary", person.imageUrl!!.contains("/Items/123/Images/Primary"))
        assertTrue(person.imageUrl!!.contains("tag=abc"))
        // Token 不进 URL
        assertTrue(!person.imageUrl!!.contains("token", ignoreCase = true))
    }

    @Test
    fun `导演映射为 DIRECTOR`() {
        val people = listOf(EmbyPersonDto(id = "456", name = "Villeneuve", type = "Director"))
        val result = EmbyMetadataMapper.mapPeople(api, people)
        assertEquals(Person.Role.DIRECTOR, result[0].role)
    }

    @Test
    fun `无 id 的人员被跳过`() {
        val people = listOf(
            EmbyPersonDto(id = null, name = "NoId"),
            EmbyPersonDto(id = "789", name = "HasId"),
        )
        val result = EmbyMetadataMapper.mapPeople(api, people)
        assertEquals(1, result.size)
        assertEquals("HasId", result[0].name)
    }

    @Test
    fun `null people 返回空列表`() {
        val result = EmbyMetadataMapper.mapPeople(api, null)
        assertTrue(result.isEmpty())
    }

    // ---- Studios ----

    @Test
    fun `studios 映射提取 name`() {
        val studios = listOf(EmbyStudioDto(name = "MGM"), EmbyStudioDto(name = "HBO"))
        val result = EmbyMetadataMapper.mapStudios(studios)
        assertEquals(listOf("MGM", "HBO"), result)
    }

    @Test
    fun `null studios 返回空列表`() {
        val result = EmbyMetadataMapper.mapStudios(null)
        assertTrue(result.isEmpty())
    }
}