package com.mediahub.metadata

import com.mediahub.metadata.ratings.PlatformRating
import com.mediahub.metadata.ratings.RatingPlatform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可注入配置点（本轮 UI 接线的前置）：
 * - 缺省密钥必须降级为「不可用 + 零网络请求」，绝不崩溃；
 * - 只有配了对应密钥才装配 TMDB / MDBList。
 */
class MetadataEngineTest {

    private class RecordingHttp(private val body: String = "{}") : MetadataHttpClient {
        val urls = mutableListOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): String {
            urls += url
            return body
        }
    }

    @Test
    fun `blank configuration degrades without any network request`() = runBlocking {
        val http = RecordingHttp()
        val engine = MetadataEngine.create(MetadataRemoteConfig(), http)

        assertFalse(engine.available)
        val outcome = engine.manualMatch.search("沙丘")
        assertTrue(outcome.candidates.isEmpty())
        assertTrue(outcome.failures.isEmpty())
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun `engine without keys exposes empty registry`() {
        val engine = MetadataEngine.create(MetadataRemoteConfig(), RecordingHttp())

        assertFalse(engine.available)
        assertNull(engine.registry.providerFor(MetadataSource.TMDB))
    }

    @Test
    fun `tmdb key wires provider and enables availability`() {
        val engine = MetadataEngine.create(
            MetadataRemoteConfig(tmdbApiKey = "tmdb-test-key"),
            RecordingHttp(),
        )

        assertTrue(engine.available)
        assertTrue(engine.registry.providerFor(MetadataSource.TMDB) != null)
    }

    @Test
    fun `tmdb rating still aggregates when no external source is configured`() = runBlocking {
        val http = RecordingHttp()
        val engine = MetadataEngine.create(MetadataRemoteConfig(tmdbApiKey = "tmdb-test-key"), http)

        val outcome = engine.ratings.load(
            MetadataDetail(
                source = MetadataSource.TMDB,
                id = "movie/550",
                title = "搏击俱乐部",
                rating = 8.4,
            ),
        )

        assertEquals(listOf(RatingPlatform.TMDB), outcome.ratings.ratings.map(PlatformRating::platform))
        assertTrue(outcome.failures.isEmpty())
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun `mdbList key assembles external ratings source`() = runBlocking {
        val http = RecordingHttp(
            """{"ratings":[{"source":"imdb","value":8.8},{"source":"tomatoes","value":92}]}""",
        )
        val engine = MetadataEngine.create(
            MetadataRemoteConfig(
                tmdbApiKey = "",
                mdbListApiKey = "mdb-test-key",
                mdbListBaseUrl = "https://mdblist.test",
            ),
            http,
        )

        val outcome = engine.ratings.load(
            MetadataDetail(
                source = MetadataSource.TMDB,
                id = "movie/550",
                title = "搏击俱乐部",
                rating = 8.4,
                externalIds = mapOf("imdb" to "tt0137523"),
            ),
        )

        assertEquals(1, http.urls.size)
        assertTrue(http.urls.single().startsWith("https://mdblist.test/imdb/tt0137523"))
        assertEquals(
            listOf(RatingPlatform.TMDB, RatingPlatform.IMDB, RatingPlatform.ROTTEN_TOMATOES),
            outcome.ratings.ratings.map(PlatformRating::platform),
        )
    }
}
