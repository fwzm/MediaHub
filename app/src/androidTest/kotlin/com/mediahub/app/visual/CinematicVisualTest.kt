package com.mediahub.app.visual

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediahub.app.ui.theme.MediaHubTheme
import com.mediahub.feature.detail.DetailBody
import com.mediahub.feature.detail.SeriesBrowseState
import com.mediahub.feature.home.HomeContent
import com.mediahub.feature.library.LibraryContent
import com.mediahub.feature.library.LibraryUiState
import com.mediahub.feature.search.SearchContent
import com.mediahub.feature.search.engine.GlobalSearchState
import com.mediahub.feature.search.engine.UnifiedSearchHit
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerType
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CinematicVisualTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<CinematicVisualActivity>()

    @Test
    fun homeContinueWatchingUsesProductionCardAndDispatchesSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val progress = PlaybackProgress(
            serverId = "fixture", itemId = "midnight", positionMs = 38 * 60_000L,
            durationMs = 112 * 60_000L, isPaused = true, updatedAtEpochMs = 0,
            itemTitle = "午夜档案", posterUrl = image(context, "midnight", landscape = true),
        )
        var selected = ""
        setContent {
            HomeContent(
                servers = listOf(
                    MediaServer("fixture", "我的家庭影院", ServerType.EMBY, "", isDefault = true, createdAtEpochMs = 0),
                    MediaServer("local", "本地影片", ServerType.LOCAL, "", createdAtEpochMs = 0),
                ),
                loaded = true,
                continueWatching = listOf(
                    progress,
                    progress.copy(
                        itemId = "voyage", itemTitle = "星际漫游", positionMs = 24 * 60_000L,
                        posterUrl = image(context, "voyage", landscape = true),
                    ),
                ),
                needsRelogin = { false }, authState = { null },
                onOpenServer = {}, onRelogin = {}, onEditServer = {}, onLogout = {},
                onAddServer = {}, onOpenSettings = {}, onOpenSearch = {},
                onOpenItem = { selected = it.itemId },
            )
        }
        composeRule.onNodeWithText("午夜档案").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("已看 33%").assertIsDisplayed()
        composeRule.onNodeWithText("2 个媒体源").assertIsDisplayed()
        assertEquals("midnight", selected)
        capture("home")
    }

    @Test
    fun libraryLongTitlesRemainVisibleAndPlayable() {
        val titles = listOf(
            "星际漫游", "午夜档案", "最后的灯塔",
            "风之谷：那些被时间珍藏的故事与漫长旅程", "山海之间", "城市微光",
        )
        val items = titles.mapIndexed { index, title ->
            item(index, title, if (index % 2 == 0) MediaType.MOVIE else MediaType.SERIES)
        }
        var selected = ""
        setContent {
            LibraryContent(
                state = LibraryUiState.Content(items = items, libraryName = "精选电影"),
                fallbackName = "精选电影", onBack = {}, onOpenLibrary = {},
                onOpenItem = { selected = it.id }, onOpenFavorites = null,
                onRefresh = {}, onLoadMore = {}, onGoToParent = {}, onOpenFolder = {},
                onSortSelected = {}, onFilterSelected = {},
            )
        }
        composeRule.onNodeWithText("精选电影").assertIsDisplayed()
        composeRule.onNodeWithText("星际漫游").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("风之谷：那些被时间珍藏的故事与漫长旅程", substring = true).assertIsDisplayed()
        assertEquals("0", selected)
        capture("library")
    }

    @Test
    fun searchProductionCardsKeepTitleAndSourceHierarchy() {
        val hits = listOf("星际漫游", "午夜档案", "最后的灯塔").mapIndexed { index, title ->
            UnifiedSearchHit(item(index, title, MediaType.MOVIE), if (index == 0) "我的家庭影院" else "本地影片")
        }
        var query = "星际"
        setContent {
            SearchContent(
                query = query,
                state = GlobalSearchState(query = query, hits = hits),
                onQueryChange = { query = it },
                onBack = {},
                onOpenItem = {},
            )
        }
        composeRule.onNodeWithText("星际").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("清空").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals("", query)
        composeRule.onNodeWithText("星际漫游").assertIsDisplayed()
        composeRule.onNodeWithText("我的家庭影院").assertIsDisplayed()
        composeRule.onNodeWithText("午夜档案").assertIsDisplayed()
        capture("search")
    }

    @Test
    fun cinematicDetailRetainsRealPlayCallback() {
        val movie = item(0, "星际漫游", MediaType.MOVIE).copy(
            year = 2026, runtimeMs = 128 * 60_000L, communityRating = 8.6,
            genres = listOf("科幻", "冒险"), container = "mkv",
            overview = "在遥远的星系边缘，一支探索队踏上寻找新家园的旅程。穿过未知星云，他们发现宇宙深处隐藏着关于时间与记忆的答案。这是一段关于勇气、探索与彼此守护的故事。",
        )
        var played = false
        setContent {
            DetailBody(
                detail = MediaDetail(movie), seriesState = SeriesBrowseState(),
                onPlay = { played = true },
            )
        }
        composeRule.onNodeWithText("星际漫游").assertIsDisplayed()
        composeRule.onNodeWithText("科幻").assertIsDisplayed()
        composeRule.onNodeWithText("立即播放").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("剧情简介").assertIsDisplayed()
        composeRule.waitForIdle()
        assert(played)
        capture("detail")
    }

    private fun item(index: Int, title: String, type: MediaType): MediaItem {
        val artwork = listOf("voyage", "midnight", "lighthouse")[index % 3]
        return MediaItem(
            serverId = "fixture", id = index.toString(), type = type, title = title,
            posterUrl = image(InstrumentationRegistry.getInstrumentation().targetContext, artwork, landscape = false),
            backdropUrl = image(InstrumentationRegistry.getInstrumentation().targetContext, artwork, landscape = true),
        )
    }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MediaHubTheme(content = content)
            }
        }
        composeRule.waitForIdle()
    }

    private fun image(context: Context, name: String, landscape: Boolean): String {
        val suffix = if (landscape) "landscape" else "poster"
        val destination = File(context.cacheDir, "cinematic-$name-$suffix.png")
        if (!destination.exists()) {
            InstrumentationRegistry.getInstrumentation().context.assets.open("$name-$suffix.png").use { input ->
                destination.outputStream().use(input::copyTo)
            }
        }
        return destination.absolutePath
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(400)
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(instrumentation.targetContext.cacheDir, "cinematic")
        check(directory.isDirectory || directory.mkdirs())
        FileOutputStream(File(directory, "$name.png")).use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
