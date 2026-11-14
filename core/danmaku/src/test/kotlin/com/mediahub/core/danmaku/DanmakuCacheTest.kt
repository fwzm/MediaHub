package com.mediahub.core.danmaku

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DanmakuCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `file cache round trips and isolates keys`() = runBlocking {
        val cache = FileDanmakuCache(temp.root, dispatcher = Dispatchers.Unconfined)

        cache.write("episode:1", "payload-one")
        cache.write("episode:2", "payload-two")

        assertEquals("payload-one", cache.read("episode:1"))
        assertEquals("payload-two", cache.read("episode:2"))
        assertNull(cache.read("episode:3"))
    }

    @Test
    fun `expired entry is ignored`() = runBlocking {
        val cache = FileDanmakuCache(
            directory = temp.root,
            ttlMs = 1_000L,
            dispatcher = Dispatchers.Unconfined,
        )
        cache.write("episode:1", "payload")

        val file = temp.root.listFiles()!!.single()
        file.setLastModified(System.currentTimeMillis() - 5_000L)

        assertNull(cache.read("episode:1"))
    }

    @Test
    fun `reads tolerate missing directory and corrupt files`() = runBlocking {
        val missing = File(temp.root, "not-created")
        val cache = FileDanmakuCache(missing, dispatcher = Dispatchers.Unconfined)
        assertNull(cache.read("x"))

        cache.write("x", "ok")
        missing.listFiles()!!.single().writeText("")
        assertEquals("", cache.read("x"))
    }
}
