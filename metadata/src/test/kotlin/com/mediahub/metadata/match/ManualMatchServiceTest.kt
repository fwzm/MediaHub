package com.mediahub.metadata.match

import com.mediahub.metadata.MetadataDetail
import com.mediahub.metadata.MetadataException
import com.mediahub.metadata.MetadataMediaType
import com.mediahub.metadata.MetadataProvider
import com.mediahub.metadata.MetadataResult
import com.mediahub.metadata.MetadataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualMatchServiceTest {

    @Test
    fun `aggregates providers and sorts by match score`() = runBlocking {
        val tmdb = FakeProvider(
            MetadataSource.TMDB,
            listOf(result("tv/exact", "绝命毒师", 2008)),
        )
        val bangumi = FakeProvider(
            MetadataSource.BANGUMI,
            listOf(result("bgm/1", "绝命毒师 续命之徒", 2019)),
        )
        val service = ManualMatchService(listOf(tmdb, bangumi))

        val outcome = service.search("绝命毒师 2008")

        assertEquals(listOf("tv/exact", "bgm/1"), outcome.candidates.map { it.result.id })
        assertEquals(2008, outcome.query.year)
        assertTrue(outcome.failures.isEmpty())
    }

    @Test
    fun `provider failure is recorded while other results survive`() = runBlocking {
        val broken = FakeProvider(
            MetadataSource.TMDB,
            emptyList(),
            MetadataException(429, "限流"),
        )
        val working = FakeProvider(
            MetadataSource.BANGUMI,
            listOf(result("bgm/1", "绝命毒师", 2008)),
        )
        val service = ManualMatchService(listOf(broken, working))

        val outcome = service.search("绝命毒师")

        assertEquals(listOf("bgm/1"), outcome.candidates.map { it.result.id })
        assertEquals(1, outcome.failures.size)
        assertEquals(MetadataSource.TMDB, outcome.failures.single().source)
        assertEquals(429, outcome.failures.single().statusCode)
    }

    @Test
    fun `blank query does not hit providers`() = runBlocking {
        val provider = FakeProvider(MetadataSource.TMDB, listOf(result("tv/1", "绝命毒师", 2008)))
        val service = ManualMatchService(listOf(provider))

        val outcome = service.search("   ")

        assertTrue(outcome.candidates.isEmpty())
        assertEquals(0, provider.calls)
    }

    @Test
    fun `explicit year is used when query has none`() = runBlocking {
        val provider = FakeProvider(
            MetadataSource.TMDB,
            listOf(
                result("tv/new", "绝命毒师", 2008, language = "en"),
                result("tv/old", "绝命毒师", 1999, language = "en"),
            ),
        )
        val service = ManualMatchService(listOf(provider))

        val outcome = service.search("绝命毒师", year = 2008)

        assertEquals("tv/new", outcome.candidates.first().result.id)
    }

    @Test
    fun `tied top candidates are marked ambiguous`() = runBlocking {
        val tmdb = FakeProvider(MetadataSource.TMDB, listOf(result("tv/1", "绝命毒师", 2008)))
        val bangumi = FakeProvider(MetadataSource.BANGUMI, listOf(result("bgm/1", "绝命毒师", 2008)))
        val outcome = ManualMatchService(listOf(tmdb, bangumi)).search("绝命毒师")

        assertTrue(outcome.ambiguous)
        assertEquals(2, outcome.candidates.size)
    }

    @Test
    fun `clear winner is not ambiguous`() = runBlocking {
        val provider = FakeProvider(
            MetadataSource.TMDB,
            listOf(
                result("tv/exact", "绝命毒师", 2008),
                result("tv/other", "绝命毒师 续命之徒", 2019),
            ),
        )

        val outcome = ManualMatchService(listOf(provider)).search("绝命毒师 2008")

        assertTrue(!outcome.ambiguous)
    }

    private class FakeProvider(
        override val source: MetadataSource,
        private val results: List<MetadataResult>,
        private val error: Exception? = null,
    ) : MetadataProvider {
        var calls: Int = 0
            private set

        override suspend fun search(query: String, year: Int?): List<MetadataResult> {
            calls++
            error?.let { throw it }
            return results
        }

        override suspend fun detail(metadataId: String): MetadataDetail? = null
    }

    private companion object {
        fun result(
            id: String,
            title: String,
            year: Int?,
            language: String? = null,
        ) = MetadataResult(
            source = MetadataSource.TMDB,
            id = id,
            title = title,
            year = year,
            language = language,
            mediaType = MetadataMediaType.SERIES,
        )
    }
}
