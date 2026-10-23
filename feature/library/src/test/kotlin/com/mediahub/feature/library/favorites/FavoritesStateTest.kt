package com.mediahub.feature.library.favorites

import com.mediahub.model.MediaItem
import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收藏列表纯状态迁移（task-25 / A2）。 */
class FavoritesStateTest {

    private fun item(id: String, type: MediaType = MediaType.MOVIE, season: Int? = null, episode: Int? = null) =
        MediaItem(
            serverId = "srv-1",
            id = id,
            type = type,
            title = "条目" + id,
            seasonNumber = season,
            episodeNumber = episode,
            isFavorite = true,
        )

    @Test
    fun `loaded empty page becomes the empty state not content`() {
        val state = FavoritesState.loaded(emptyList())

        assertTrue("空收藏应为 Empty 而非空列表 Content", state is FavoritesUiState.Empty)
        assertEquals("还没有收藏", (state as FavoritesUiState.Empty).message)
    }

    @Test
    fun `loaded keeps the server order of favorites`() {
        val items = listOf(item("m1"), item("m2"), item("m3"))

        val state = FavoritesState.loaded(items) as FavoritesUiState.Content

        assertEquals(listOf("m1", "m2", "m3"), state.items.map { it.id })
        assertNull(state.refreshError)
    }

    @Test
    fun `failed without content is a full screen error not an empty state`() {
        val state = FavoritesState.failed(FavoritesUiState.Loading, "网络不可用")

        assertTrue("加载失败不得伪装成空收藏", state is FavoritesUiState.Error)
        assertEquals("网络不可用", (state as FavoritesUiState.Error).message)
    }

    @Test
    fun `failed while content exists keeps the list and attaches a refresh error`() {
        val content = FavoritesState.loaded(listOf(item("m1"))) as FavoritesUiState.Content

        val state = FavoritesState.failed(content, "刷新失败") as FavoritesUiState.Content

        assertEquals("旧列表不得被清空", listOf("m1"), state.items.map { it.id })
        assertEquals("刷新失败", state.refreshError)
    }

    @Test
    fun `unsupported source is an explicit error`() {
        val state = FavoritesState.unsupported("Jellyfin") as FavoritesUiState.Error

        assertEquals("Jellyfin 暂不支持收藏", state.message)
    }

    @Test
    fun `subtitle labels episodes and movies`() {
        assertEquals(
            "单集 第2季 第5集",
            subtitleOf(item("e1", MediaType.EPISODE, season = 2, episode = 5)),
        )
        assertEquals("电影", subtitleOf(item("m1")))
    }
}
