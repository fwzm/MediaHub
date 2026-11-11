package com.mediahub.player.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Controlled fake credentials and loopback HTTP only. Delayed loading is intentional. */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaRequestSnapshotTest {
    private val cache = MediaCacheProvider(RuntimeEnvironment.getApplication())
    private val factory = PlayerFactory(RuntimeEnvironment.getApplication(), cache)
    private val a = MockWebServer().apply { start() }
    private val b = MockWebServer().apply { start() }
    private val prepared = mutableListOf<Pair<MediaSource, MediaSource.MediaSourceCaller>>()
    private val itemId = UUID.randomUUID().toString()

    @After fun close() {
        prepared.forEach { (source, caller) -> source.releaseSource(caller) }
        cache.cache.release()
        a.shutdown()
        b.shutdown()
    }

    @Test fun oldHlsSourceKeepsItsHeadersWhenPreparedAfterTheNextSourceIsCreated() {
        val holder = PlaybackHeadersHolder()
        holder.setHeaders(mapOf("X-Emby-Token" to "fixture-a"))
        val sources = factory.mediaSourceFactory(holder, PlaybackSpeedMonitor())
            .setDrmSessionManagerProvider(DefaultDrmSessionManagerProvider())
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
            .experimentalParseSubtitlesDuringExtraction(true)
        val old = sources.createMediaSource(MediaItem.fromUri(a.url("/a-$itemId.m3u8").toString()))
        holder.setHeaders(mapOf("X-Emby-Token" to "fixture-b"))
        val next = sources.createMediaSource(MediaItem.fromUri(b.url("/b-$itemId.m3u8").toString()))
        a.enqueue(MockResponse().setBody(PLAYLIST))
        b.enqueue(MockResponse().setBody(PLAYLIST))
        prepare(old)
        val first = a.takeRequest(5, TimeUnit.SECONDS) ?: error("Old HLS source never loaded")
        assertEquals("A late A load must not send the next session's B credential", "fixture-a", first.getHeader("X-Emby-Token"))
        prepare(next)
        val second = b.takeRequest(5, TimeUnit.SECONDS) ?: error("Next HLS source never loaded")
        assertEquals("fixture-b", second.getHeader("X-Emby-Token"))
    }

    @Test fun dataSourceFactoryKeepsItsSnapshotEvenWhenAReaderIsCreatedLater() {
        val holder = PlaybackHeadersHolder()
        holder.setHeaders(mapOf("Authorization" to "Bearer fixture-a"))
        val old = factory.mediaDataSourceFactory(holder, PlaybackSpeedMonitor())
        holder.setHeaders(mapOf("Authorization" to "Bearer fixture-b"))
        val reader = old.createDataSource()
        a.enqueue(MockResponse().setBody("fixture payload"))
        try { reader.open(DataSpec(Uri.parse(a.url("/late-reader-$itemId").toString()))) }
        finally { reader.close() }
        assertEquals("Bearer fixture-a", a.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test fun changingTheCallerMapCannotRewriteThePublishedHeaderSnapshot() {
        val callerMap = mutableMapOf("Cookie" to "fixture=a")
        val holder = PlaybackHeadersHolder()
        holder.setHeaders(callerMap)
        callerMap["Cookie"] = "fixture=b"
        assertEquals("fixture=a", holder.headers["Cookie"])
    }

    private fun prepare(source: MediaSource) {
        val caller = MediaSource.MediaSourceCaller { _, _ -> }
        source.prepareSource(caller, PlayerId.UNSET,
            DefaultBandwidthMeter.getSingletonInstance(RuntimeEnvironment.getApplication()))
        prepared += source to caller
    }

    private companion object {
        const val PLAYLIST = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nchunk.ts\n#EXT-X-ENDLIST\n"
    }
}
