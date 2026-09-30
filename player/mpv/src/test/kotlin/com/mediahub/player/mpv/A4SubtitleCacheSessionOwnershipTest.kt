package com.mediahub.player.mpv

import com.mediahub.core.logging.StdoutLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class A4SubtitleCacheSessionOwnershipTest {
    @get:Rule val tmp = TemporaryFolder()

    private class LandingBarrier {
        val landed = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val part = AtomicReference<File>()
        fun awaitCommit(file: File) {
            // Real closed-file commit window, with actual payload already written.
            if (file.readText() != "S1 subtitle") return
            part.set(file)
            landed.countDown()
            check(proceed.await(10, TimeUnit.SECONDS)) { "landing barrier not released" }
        }
        fun assertReached() {
            assertTrue("real file commit window reached", landed.await(10, TimeUnit.SECONDS))
            assertTrue(part.get().exists())
            assertEquals("S1 subtitle", part.get().readText())
            assertTrue(part.get().name.endsWith(".part"))
        }
    }

    private fun interleave(http: Boolean) = runBlocking {
        val server = MockWebServer().apply { start() }
        val barrier = LandingBarrier()
        val root = tmp.newFolder()
        val copies = mutableListOf<String>()
        val cache = SubtitleCache(root, OkHttpClient(), { uri, target ->
            synchronized(copies) { copies += uri.toString() }
            target.writeText(if (uri.toString().contains("old")) "S1 subtitle" else "S2 subtitle")
            true
        }, StdoutLogger(), barrier::awaitCommit)
        try {
            val s1 = cache.beginSession()
            if (http) server.enqueue(MockResponse().setBody("S1 subtitle"))
            val oldUri = if (http) server.url("/old.srt").toString() else "content://import/old.srt"
            val old = async(Dispatchers.IO) { cache.localPathFor(oldUri, "https://media/one", "same", emptyMap(), s1) }
            try {
                barrier.assertReached()
                if (http) assertEquals("/old.srt", server.takeRequest(10, TimeUnit.SECONDS)!!.path)
                else assertEquals(listOf(oldUri), synchronized(copies) { copies.toList() })
                cache.endSession(s1)
                val s2 = cache.beginSession()
                if (http) server.enqueue(MockResponse().setBody("S2 subtitle"))
                val newUri = if (http) server.url("/new.srt").toString() else "content://import/new.srt"
                val current = cache.localPathFor(newUri, "https://media/two", "same", emptyMap(), s2)!!
                assertEquals("S2 subtitle", File(current).readText())
                // Old end/clear arriving after S2 is fully committed cannot invalidate S2 or delete it.
                cache.endSession(s1); cache.clearSession(s1)
                barrier.proceed.countDown()
                assertNull("old token cannot revive after begin S2", old.await())
                assertEquals("S2 subtitle", File(current).readText())
                assertEquals(current, cache.localPathFor(newUri, "https://media/two", "same", emptyMap(), s2))
                val files = File(root, "subtitles").listFiles().orEmpty()
                assertEquals(listOf(File(current).name), files.map { it.name })
                cache.endSession(s2)
                assertTrue(File(root, "subtitles").listFiles().orEmpty().isEmpty())
            } finally { barrier.proceed.countDown(); old.cancelAndJoin() }
        } finally { server.shutdown() }
    }

    @Test fun `http landing S1 stop S2 old completion and old cleanup are isolated`() = interleave(true)
    @Test fun `saf landing S1 stop S2 old completion and old cleanup are isolated`() = interleave(false)

    @Test fun `cancel at real SAF publication window leaves no formal or part file`() = runBlocking {
        val barrier = LandingBarrier()
        val root = tmp.newFolder()
        val cache = SubtitleCache(root, OkHttpClient(), { _, target -> target.writeText("S1 subtitle"); true }, StdoutLogger(), barrier::awaitCommit)
        val job = async(Dispatchers.IO) { cache.localPathFor("content://import/old.srt", "https://media/one", "scope", emptyMap()) }
        try {
            barrier.assertReached()
            job.cancel()
            barrier.proceed.countDown()
            job.join()
            assertTrue(File(root, "subtitles").listFiles().orEmpty().isEmpty())
        } finally { barrier.proceed.countDown(); job.cancelAndJoin() }
    }

    @Test fun `concurrent import does not delete a live part while another subtitle commits`() = runBlocking {
        val barrier = LandingBarrier()
        val root = tmp.newFolder()
        val cache = SubtitleCache(root, OkHttpClient(), { uri, target -> target.writeText(if (uri.toString().contains("old")) "S1 subtitle" else "other"); true }, StdoutLogger(), barrier::awaitCommit)
        val first = async(Dispatchers.IO) { cache.localPathFor("content://import/old.srt", "https://media/one", "scope", emptyMap()) }
        try {
            barrier.assertReached()
            val second = cache.localPathFor("content://import/new.srt", "https://media/one", "scope", emptyMap())!!
            assertTrue(barrier.part.get().exists())
            assertEquals("other", File(second).readText())
            barrier.proceed.countDown()
            assertEquals("S1 subtitle", File(first.await()!!).readText())
            assertEquals(2, File(root, "subtitles").listFiles().orEmpty().size)
        } finally { barrier.proceed.countDown(); first.cancelAndJoin(); cache.endSession() }
    }

    @Test fun `same scope same filename different URI keeps distinct content`() = runBlocking {
        val cache = SubtitleCache(tmp.newFolder(), OkHttpClient(), { uri, target -> target.writeText(uri.toString()); true }, StdoutLogger())
        val a = cache.localPathFor("content://import/a/zh.srt", "https://media/one", "same", emptyMap())!!
        val b = cache.localPathFor("content://import/b/zh.srt", "https://media/one", "same", emptyMap())!!
        assertNotEquals(a, b)
        assertEquals("content://import/a/zh.srt", File(a).readText())
        assertEquals("content://import/b/zh.srt", File(b).readText())
        cache.endSession()
    }
    @Test fun `another cache instance cannot delete active parts or delivered subtitles`() = runBlocking {
        val root = tmp.newFolder()
        val barrier = LandingBarrier()
        val a = SubtitleCache(root, OkHttpClient(), { _, target -> target.writeText("S1 subtitle"); true }, StdoutLogger(), barrier::awaitCommit)
        val b = SubtitleCache(root, OkHttpClient(), { _, target -> target.writeText("other instance"); true }, StdoutLogger())
        val pending = async(Dispatchers.IO) { a.localPathFor("content://import/old.srt", "https://media/one", "scope", emptyMap()) }
        try {
            barrier.assertReached()
            val delivered = b.localPathFor("content://import/new.srt", "https://media/two", "scope", emptyMap())!!
            assertTrue(barrier.part.get().exists())
            barrier.proceed.countDown()
            val first = pending.await()!!
            a.endSession()
            assertFalse(File(first).exists())
            assertEquals("other instance", File(delivered).readText())
        } finally { barrier.proceed.countDown(); pending.cancelAndJoin(); a.endSession(); b.endSession() }
    }

}
