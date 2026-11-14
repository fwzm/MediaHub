package com.mediahub.app.metadata

import com.mediahub.metadata.MetadataMediaType
import com.mediahub.metadata.MetadataSource
import com.mediahub.metadata.match.MetadataMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 手动匹配结果写回（SharedPreferences，不引入 Room 迁移）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPrefsMetadataMatchStoreTest {

    private fun store(): SharedPrefsMetadataMatchStore =
        SharedPrefsMetadataMatchStore(RuntimeEnvironment.getApplication())

    @Test
    fun `round trips a match per item`() = runBlocking {
        val store = store()
        val match = MetadataMatch(
            source = MetadataSource.TMDB,
            metadataId = "movie/550",
            title = "搏击俱乐部",
            year = 1999,
            posterUrl = "https://image.test/poster.jpg",
            mediaType = MetadataMediaType.MOVIE,
        )

        store.put("srv-roundtrip", "item-1", match)

        assertEquals(match, store.get("srv-roundtrip", "item-1"))
    }

    @Test
    fun `isolates items and servers`() = runBlocking {
        val store = store()
        store.put("srv-isolation", "item-1", MetadataMatch(MetadataSource.TMDB, "movie/550", "A", 1999))
        store.put("srv-isolation", "item-2", MetadataMatch(MetadataSource.TMDB, "tv/1396", "B", 2008))

        assertEquals("movie/550", store.get("srv-isolation", "item-1")?.metadataId)
        assertEquals("tv/1396", store.get("srv-isolation", "item-2")?.metadataId)
        assertNull(store.get("other-server", "item-1"))
        assertNull(store.get("srv-isolation", "missing"))
    }

    @Test
    fun `missing match returns null instead of throwing`() = runBlocking {
        assertNull(store().get("srv-missing", "none"))
    }
}
