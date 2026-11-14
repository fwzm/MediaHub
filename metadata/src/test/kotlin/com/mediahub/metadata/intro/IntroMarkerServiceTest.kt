package com.mediahub.metadata.intro

import com.mediahub.metadata.MetadataException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroMarkerServiceTest {

    @Test
    fun `merges overlapping markers from multiple sources and shows sources`() = runBlocking {
        val service = IntroMarkerService(
            listOf(
                FakeSource("IntroDB", priority = 20, markers = listOf(marker(MarkerType.INTRO, 66_000, 76_000, "IntroDB", 0.9))),
                FakeSource("AniSkip", priority = 10, markers = listOf(marker(MarkerType.INTRO, 65_000, 75_000, "AniSkip", 0.6))),
            ),
        )

        val result = service.resolve(query(runtimeMs = 1_440_000))

        val intro = result.markers.single()
        assertEquals(MarkerType.INTRO, intro.type)
        assertEquals(65_000L, intro.startMs)
        assertEquals(76_000L, intro.endMs)
        assertEquals("IntroDB", intro.source)
        assertEquals(setOf("IntroDB", "AniSkip"), intro.contributors)
        assertEquals(0.9, intro.confidence, 0.001)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `keeps intro and credits as separate markers`() = runBlocking {
        val service = IntroMarkerService(
            listOf(
                FakeSource(
                    "IntroDB",
                    priority = 20,
                    markers = listOf(
                        marker(MarkerType.INTRO, 60_000, 90_000, "IntroDB", 0.9),
                        marker(MarkerType.CREDITS, 1_320_000, 1_440_000, "IntroDB", 0.9),
                    ),
                ),
            ),
        )

        val result = service.resolve(query(runtimeMs = 1_440_000))

        assertEquals(listOf(MarkerType.INTRO, MarkerType.CREDITS), result.markers.map { it.type })
        assertEquals(listOf(60_000L, 1_320_000L), result.markers.map { it.startMs })
    }

    @Test
    fun `absolute markers stay exact when runtime is missing`() = runBlocking {
        val service = IntroMarkerService(
            listOf(
                FakeSource(
                    "AniSkip",
                    priority = 10,
                    markers = listOf(marker(MarkerType.INTRO, 85_000, 115_000, "AniSkip", 0.7)),
                ),
            ),
        )

        val result = service.resolve(query(runtimeMs = null))

        val intro = result.markers.single()
        assertEquals(85_000L, intro.startMs)
        assertEquals(115_000L, intro.endMs)
    }

    @Test
    fun `markers starting after known runtime are dropped`() = runBlocking {
        val service = IntroMarkerService(
            listOf(
                FakeSource(
                    "IntroDB",
                    priority = 20,
                    markers = listOf(
                        marker(MarkerType.INTRO, 60_000, 90_000, "IntroDB", 0.9),
                        marker(MarkerType.CREDITS, 1_500_000, 1_560_000, "IntroDB", 0.9),
                    ),
                ),
            ),
        )

        val result = service.resolve(query(runtimeMs = 1_440_000))

        assertEquals(listOf(MarkerType.INTRO), result.markers.map { it.type })
    }

    @Test
    fun `invalid marker intervals are dropped`() = runBlocking {
        val service = IntroMarkerService(
            listOf(
                FakeSource(
                    "Broken",
                    priority = 1,
                    markers = listOf(
                        marker(MarkerType.INTRO, 90_000, 90_000, "Broken", 0.5),
                        marker(MarkerType.CREDITS, 120_000, 110_000, "Broken", 0.5),
                    ),
                ),
            ),
        )

        val result = service.resolve(query(runtimeMs = null))

        assertTrue(result.markers.isEmpty())
    }

    @Test
    fun `failing source is recorded while other markers survive`() = runBlocking {
        val service = IntroMarkerService(
            listOf(
                FailingSource("Broken", MetadataException(503, "不可用")),
                FakeSource(
                    "AniSkip",
                    priority = 10,
                    markers = listOf(marker(MarkerType.INTRO, 85_000, 115_000, "AniSkip", 0.7)),
                ),
            ),
        )

        val result = service.resolve(query(runtimeMs = null))

        assertEquals(1, result.markers.size)
        assertEquals("Broken", result.failures.single().source)
        assertEquals(503, result.failures.single().statusCode)
    }

    @Test
    fun `no markers yields empty resolution`() = runBlocking {
        val result = IntroMarkerService(emptyList()).resolve(query(runtimeMs = 1_440_000))

        assertTrue(result.markers.isEmpty())
        assertTrue(result.failures.isEmpty())
        assertNull(result.markers.firstOrNull())
    }

    private class FakeSource(
        override val name: String,
        override val priority: Int,
        private val markers: List<MediaMarker>,
    ) : IntroMarkerSource {
        override suspend fun markers(query: MarkerQuery): List<MediaMarker> = markers
    }

    private class FailingSource(
        override val name: String,
        private val error: Exception,
    ) : IntroMarkerSource {
        override val priority: Int = 1
        override suspend fun markers(query: MarkerQuery): List<MediaMarker> = throw error
    }

    private fun marker(
        type: MarkerType,
        startMs: Long,
        endMs: Long?,
        source: String,
        confidence: Double,
    ) = MediaMarker(
        type = type,
        startMs = startMs,
        endMs = endMs,
        source = source,
        confidence = confidence,
        contributors = setOf(source),
    )

    private fun query(runtimeMs: Long?) = MarkerQuery(
        title = "绝命毒师",
        imdbId = "tt0903747",
        tmdbId = "1396",
        season = 1,
        episode = 1,
        runtimeMs = runtimeMs,
    )
}
