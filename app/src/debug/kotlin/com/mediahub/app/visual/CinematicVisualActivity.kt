package com.mediahub.app.visual

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mediahub.feature.detail.DetailBody
import com.mediahub.feature.detail.SeriesBrowseState
import com.mediahub.feature.home.HomeContent
import com.mediahub.feature.library.LibraryContent
import com.mediahub.feature.library.LibraryUiState
import com.mediahub.feature.search.SearchBody
import com.mediahub.feature.search.engine.GlobalSearchState
import com.mediahub.feature.search.engine.UnifiedSearchHit
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerType

class CinematicVisualActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderScreen()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderScreen()
    }

    private fun renderScreen() {
        val screen = intent.getStringExtra("screen") ?: "home"
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFADC6FF), onPrimary = Color(0xFF0B1D42),
                    primaryContainer = Color(0xFF263A68), onPrimaryContainer = Color(0xFFD9E2FF),
                    background = Color(0xFF0A0D14), onBackground = Color(0xFFE4E8F2),
                    surface = Color(0xFF0A0D14), onSurface = Color(0xFFE4E8F2),
                    surfaceContainerLow = Color(0xFF111620), surfaceContainer = Color(0xFF161C28),
                    surfaceContainerHigh = Color(0xFF1D2432), surfaceContainerHighest = Color(0xFF252D3C),
                    surfaceVariant = Color(0xFF252D3C), onSurfaceVariant = Color(0xFFAFB9CE),
                ),
                shapes = Shapes(
                    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
                    large = RoundedCornerShape(24.dp),
                ),
            ) {
                when (screen) {
                    "library" -> LibraryContent(
                        state = LibraryUiState.Content(demoItems, "精选电影"), fallbackName = "精选电影",
                        onBack = {}, onOpenLibrary = {}, onOpenItem = {}, onOpenFavorites = null,
                        onRefresh = {}, onLoadMore = {}, onGoToParent = {}, onOpenFolder = {},
                        onSortSelected = {}, onFilterSelected = {},
                    )
                    "search" -> SearchBody(
                        state = GlobalSearchState("星际", demoItems.map {
                            UnifiedSearchHit(it, if (it.id == "0") "我的家庭影院" else "本地影片")
                        }),
                        onOpenItem = {},
                    )
                    "detail" -> DetailBody(
                        detail = MediaDetail(
                            demoItems.first().copy(
                                year = 2026, runtimeMs = 128 * 60_000L, communityRating = 8.6,
                                genres = listOf("科幻", "冒险"), container = "mkv",
                                overview = "在遥远的星系边缘，一支探索队踏上寻找新家园的旅程。穿过未知星云，他们发现宇宙深处隐藏着关于时间与记忆的答案。这是一段关于勇气、探索与彼此守护的故事。",
                            ),
                        ),
                        seriesState = SeriesBrowseState(), onPlay = {},
                    )
                    else -> HomeContent(
                        servers = listOf(
                            MediaServer("fixture", "我的家庭影院", ServerType.EMBY, "", isDefault = true, createdAtEpochMs = 0),
                            MediaServer("local", "本地影片", ServerType.LOCAL, "", createdAtEpochMs = 0),
                        ),
                        loaded = true,
                        continueWatching = listOf("midnight", "voyage").mapIndexed { index, artwork ->
                            PlaybackProgress(
                                serverId = "fixture", itemId = artwork, positionMs = if (index == 0) 38 * 60_000L else 24 * 60_000L,
                                durationMs = 112 * 60_000L, isPaused = true, updatedAtEpochMs = 0,
                                itemTitle = if (index == 0) "午夜档案" else "星际漫游",
                                posterUrl = "$filesDir/cinematic/$artwork-landscape.png",
                            )
                        },
                        needsRelogin = { false }, authState = { null }, onOpenServer = {}, onRelogin = {},
                        onEditServer = {}, onLogout = {}, onAddServer = {}, onOpenSettings = {},
                        onOpenSearch = {}, onOpenItem = {},
                    )
                }
            }
        }
    }

    private val demoItems by lazy {
        listOf(
            "星际漫游", "午夜档案", "最后的灯塔",
            "风之谷：那些被时间珍藏的故事与漫长旅程", "山海之间", "城市微光",
        ).mapIndexed { index, title ->
            val artwork = listOf("voyage", "midnight", "lighthouse")[index % 3]
            MediaItem(
                serverId = "fixture", id = index.toString(),
                type = if (index % 2 == 0) MediaType.MOVIE else MediaType.SERIES, title = title,
                posterUrl = "$filesDir/cinematic/$artwork-poster.png",
                backdropUrl = "$filesDir/cinematic/$artwork-landscape.png",
            )
        }
    }
}
