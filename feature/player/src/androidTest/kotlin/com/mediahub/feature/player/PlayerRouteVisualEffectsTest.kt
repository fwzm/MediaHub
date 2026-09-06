package com.mediahub.feature.player

import android.Manifest
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediahub.core.common.NavArgCodec
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.prefs.UserPreferencesStore
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.ui.effects.PlayerVisualTestTags
import com.mediahub.core.ui.effects.PlayerVisualSemantics
import com.mediahub.core.ui.effects.PlayerVisualMaskConfig
import com.mediahub.model.MediaDetail
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlaybackSource
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.PlayerVisualPreset
import com.mediahub.model.ServerType
import com.mediahub.model.UserPreferences
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.InMemoryEnginePreferenceHistory
import com.mediahub.player.engine.PlaybackEngineCreator
import com.mediahub.player.engine.PlaybackEnginePort
import com.mediahub.player.engine.PlaybackEvent
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.PlaybackUiState
import com.mediahub.player.engine.SeekMode
import com.mediahub.player.engine.TrackSelection
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaDetailProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerRouteVisualEffectsTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val routeVisible = mutableStateOf(false)
    private val viewModelStores = mutableListOf<ViewModelStore>()
    private var originalPreferences: UserPreferences? = null
    private var preferencesStore: UserPreferencesStore? = null

    @After
    fun restoreTestState() {
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            routeVisible.value = false
            viewModelStores.forEach(ViewModelStore::clear)
        }
        val original = originalPreferences ?: return
        runBlocking { preferencesStore?.update { original } }
    }

    @Test
    fun productionPlayerRoutePersistsAllPresetsIntensityAndOffAcrossReentry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = UserPreferencesStore(context)
        preferencesStore = preferences
        originalPreferences = runBlocking { preferences.flow.first() }
        // This library's instrumentation APK is the target, never the installed MediaHub app.
        // Grant only its declared permission so selecting Spectrum cannot show an OS dialog.
        check(context.packageName == "com.mediahub.feature.player.test")
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        runBlocking {
            preferences.update {
                UserPreferences(
                    autoLandscape = false,
                    immersiveBars = false,
                    playerVisualEffects = PlayerVisualEffectsPreferences.Default.copy(enabled = false),
                )
            }
        }
        val viewModel = createViewModel(preferences)
        val currentViewModel = mutableStateOf(viewModel)
        routeVisible.value = true

        composeRule.setContent {
            MaterialTheme {
                if (routeVisible.value) {
                    PlayerRoute(onBack = {}, viewModel = currentViewModel.value)
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.resolveState.value is ResolveState.Ready &&
                viewModel.preferences.value?.playerVisualEffects?.enabled == false
        }

        openSheet()

        // The production route owns a real frame clock once a preset is enabled. Freeze the
        // Compose test clock after the sheet entrance animation so Espresso can synchronize the
        // persistence assertions without treating the intentionally-running renderer as idle.
        composeRule.mainClock.autoAdvance = false

        selectPreset(PlayerVisualTestTags.PRESET_AURORA, PlayerVisualPreset.AURORA, viewModel)
        assertEquals(PlayerVisualEffectsPreferences.DEFAULT_INTENSITY, persistedVisual().intensity, 0f)
        capturePreview("aurora")
        closeSheet()
        captureVisualEvidence(composeRule, "player-default")
        openSheet()
        capturePreview("visual-entry")

        selectPreset(PlayerVisualTestTags.PRESET_LIQUID, PlayerVisualPreset.LIQUID, viewModel)
        capturePreview("liquid")

        selectPreset(PlayerVisualTestTags.PRESET_SPECTRUM, PlayerVisualPreset.SPECTRUM, viewModel)
        // FakeEngine deliberately publishes no spectrum. The real route must time out into
        // the explicit unavailable state, not claim that this fixture has healthy audio.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onAllNodesWithTag(PlayerVisualTestTags.AUDIO_UNAVAILABLE)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(PlayerVisualTestTags.AUDIO_UNAVAILABLE)
            .performScrollToWithClock(composeRule)
            .assertIsDisplayed()
        capturePreview("spectrum")

        composeRule.onNodeWithTag(PlayerVisualTestTags.INTENSITY)
            .performScrollToWithClock(composeRule)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.65f) }
        awaitVisual(viewModel) { it.intensity == 0.65f }
        assertEquals(0.65f, persistedVisual().intensity, 0.001f)

        composeRule.onNodeWithTag(PlayerVisualTestTags.PRESET_OFF)
            .performScrollToWithClock(composeRule)
            .performClick()
        awaitVisual(viewModel) { !it.enabled && it.preset == PlayerVisualPreset.SPECTRUM }
        assertOverlay(PlayerVisualPreset.SPECTRUM, expectedFps = 0)
        capturePreview("off")
        val storedOff = persistedVisual()
        assertFalse(storedOff.enabled)
        assertEquals(PlayerVisualPreset.SPECTRUM, storedOff.preset)
        assertEquals(0.65f, storedOff.intensity, 0.001f)
        closeSheet()

        // Dispose the actual route and controller, construct a new repository/controller, then
        // re-enter. This proves screen reentry with DataStore (not a process-death claim).
        composeRule.runOnUiThread { routeVisible.value = false }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_ENTRY).assertDoesNotExist()
        composeRule.runOnUiThread { viewModelStores.first().clear() }
        val reopenedStore = UserPreferencesStore(context)
        assertEquals(storedOff, runBlocking { reopenedStore.flow.first() }.playerVisualEffects)
        val reopenedViewModel = createViewModel(reopenedStore)
        composeRule.runOnUiThread {
            currentViewModel.value = reopenedViewModel
            routeVisible.value = true
        }
        awaitVisual(reopenedViewModel) { it == storedOff }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.mainClock.advanceTimeByFrame()
            reopenedViewModel.resolveState.value is ResolveState.Ready
        }
        assertOverlay(PlayerVisualPreset.SPECTRUM, expectedFps = 0)
        openSheet()
        composeRule.onNodeWithTag(PlayerVisualTestTags.PRESET_OFF)
            .performScrollToWithClock(composeRule)
            .assertIsDisplayed()
            .assertIsSelected()
        assertEquals(storedOff, persistedVisual())
    }

    private fun openSheet() {
        Log.i("PlayerVisualPathTest", "openSheet: click entry")
        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_ENTRY).assertIsDisplayed().performClick()
        // A visible sliver is not a completed entrance. Await the actual expanded geometry.
        expandVisualSheetWithClock(composeRule)
        Log.i("PlayerVisualPathTest", "openSheet: visible")
    }

    private fun closeSheet() {
        Log.i("PlayerVisualPathTest", "closeSheet: dismiss")
        // Material3 Back collapses an expanded sheet before dismissing it. Use the real
        // user-accessible Dismiss action on its drag handle, which animates directly to Hidden.
        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.Dismiss) { dismiss ->
            assertTrue("the sheet must accept its accessibility dismiss action", dismiss())
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.mainClock.advanceTimeBy(50)
            composeRule.onAllNodesWithTag(PlayerVisualTestTags.SETTINGS)
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(PlayerVisualTestTags.SETTINGS).assertDoesNotExist()
        Log.i("PlayerVisualPathTest", "closeSheet: gone")
    }

    private fun selectPreset(tag: String, preset: PlayerVisualPreset, viewModel: PlayerViewModel) {
        Log.i("PlayerVisualPathTest", "selectPreset: $preset scroll/click")
        composeRule.onNodeWithTag(tag).performScrollToWithClock(composeRule).performClick()
        Log.i("PlayerVisualPathTest", "selectPreset: $preset await preferences")
        awaitVisual(viewModel) { it.enabled && it.preset == preset }
        assertTrue(persistedVisual().enabled)
        assertEquals(preset, persistedVisual().preset)
        composeRule.onNodeWithTag(PlayerVisualTestTags.OVERLAY, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PlayerVisualSemantics.Preset, preset.name))
        assertAmbientIsBelowSubtitleSafeBand()
        Log.i("PlayerVisualPathTest", "selectPreset: $preset verified")
    }

    private fun assertAmbientIsBelowSubtitleSafeBand() {
        val playerBounds = composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_CONTROLS)
            .fetchSemanticsNode().boundsInRoot
        val ambientBounds = composeRule.onNodeWithTag(PlayerVisualTestTags.CHROME_AMBIENT)
            .fetchSemanticsNode().boundsInRoot
        val safeTop = playerBounds.top + playerBounds.height * PlayerVisualMaskConfig().bottomStart
        assertTrue("production ambient must not enter the subtitle-safe band", ambientBounds.top >= safeTop - 1f)
        assertTrue("visible controls must retain a nonempty bottom ambient", ambientBounds.height > 0f)
        assertEquals("ambient remains anchored to the player bottom", playerBounds.bottom, ambientBounds.bottom, 1f)
    }

    private fun awaitVisual(viewModel: PlayerViewModel, predicate: (PlayerVisualEffectsPreferences) -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.mainClock.advanceTimeByFrame()
            viewModel.preferences.value?.playerVisualEffects?.let(predicate) == true
        }
        composeRule.mainClock.advanceTimeBy(1_000)
    }

    private fun assertOverlay(preset: PlayerVisualPreset, expectedFps: Int) {
        composeRule.onNodeWithTag(PlayerVisualTestTags.OVERLAY, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PlayerVisualSemantics.Preset, preset.name))
            .assert(SemanticsMatcher.expectValue(PlayerVisualSemantics.TargetFps, expectedFps))
    }

    private fun persistedVisual(): PlayerVisualEffectsPreferences = runBlocking {
        checkNotNull(preferencesStore).flow.first().playerVisualEffects
    }

    private fun capturePreview(name: String) {
        Log.i("PlayerVisualPathTest", "preview: $name scroll")
        composeRule.onNodeWithTag(PlayerVisualTestTags.PREVIEW).performScrollToWithClock(composeRule)
        Log.i("PlayerVisualPathTest", "preview: $name visible")
        captureVisualEvidence(composeRule, name)
    }

    private fun createViewModel(preferences: UserPreferencesRepository): PlayerViewModel {
        val fixtureTitle = InstrumentationRegistry.getInstrumentation().context
            .getString(com.mediahub.feature.player.test.R.string.visual_acceptance_media_title)
        val item = MediaItem(
            serverId = "srv-1",
            id = "movie-1",
            type = MediaType.MOVIE,
            title = fixtureTitle,
            container = "mp4",
        )
        val server = MediaServer(
            id = "srv-1",
            name = "Test",
            type = ServerType.EMBY,
            baseUrl = "http://127.0.0.1:8096",
            createdAtEpochMs = 1L,
        )
        return PlayerViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "serverId" to server.id,
                    "itemId" to NavArgCodec.encode(item.id),
                    "title" to item.title,
                ),
            ),
            serverStore = FakeServerStore(server),
            progressStore = FakeProgressStore,
            registry = FakeRegistry(item),
            media3EngineFactory = PlaybackEngineCreator { FakeEngine(fixtureTitle) },
            mpvEngineFactory = PlaybackEngineCreator { FakeEngine(fixtureTitle) },
            engineHistory = InMemoryEnginePreferenceHistory(),
            userPreferencesRepository = preferences,
            artworkPaletteLoader = ArtworkPaletteLoader { _, _ -> null },
            logger = NoOpLogger,
        ).also { viewModel ->
            viewModelStores += ViewModelStore().apply { put("player", viewModel) }
        }
    }

    private class FakeServerStore(private val server: MediaServer) : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(listOf(server))
        override suspend fun getServer(id: String): MediaServer? = server.takeIf { it.id == id }
    }

    private object FakeProgressStore : ProgressStore {
        override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> = flowOf(emptyList())
        override suspend fun getResume(serverId: String, itemId: String): Long? = null
        override suspend fun save(progress: PlaybackProgress) = Unit
    }

    private class FakeRegistry(private val item: MediaItem) : MediaProviderRegistry {
        override fun factoryFor(type: ServerType): com.mediahub.provider.api.MediaProviderFactory? = null
        override val supportedTypes: Set<ServerType> = setOf(ServerType.EMBY)
        override fun descriptors(): List<ProviderDescriptor> = emptyList()
        override fun create(server: MediaServer): ProviderHandle = ProviderHandle(
            provider = FakeProvider(server.id),
            detail = object : MediaDetailProvider {
                override suspend fun getItemDetail(itemId: String): MediaDetail =
                    MediaDetail(item = item)
            },
            playback = object : MediaPlaybackProvider {
                override suspend fun resolvePlayback(
                    item: MediaItem,
                    options: PlaybackOptions,
                ): PlaybackSource = PlaybackSource(url = "https://example.invalid/movie.mp4")
            },
        )
    }

    private class FakeProvider(override val serverId: String) : MediaProvider {
        override val type: ServerType = ServerType.EMBY
        override val displayName: String = "Test"
        override val descriptor: ProviderDescriptor = ProviderDescriptor(
            id = "test",
            serverType = ServerType.EMBY,
            displayName = "Test",
            category = ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = setOf(ProviderCapability.AUTH, ProviderCapability.LIBRARY),
            authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.STABLE,
        )

        override suspend fun testConnection(): ConnectionStatus = ConnectionStatus(ok = true)
    }

    private class FakeEngine(fixtureTitle: String) : PlaybackEnginePort {
        override val kind: EngineKind = EngineKind.MEDIA3
        override val uiState: StateFlow<PlaybackUiState> = MutableStateFlow(
            PlaybackUiState(mediaTitle = fixtureTitle, durationMs = 60_000L),
        )
        override val progress: SharedFlow<PlaybackProgress> = MutableSharedFlow()
        override val events: Flow<PlaybackEvent> = MutableSharedFlow()
        override val subtitleCues: StateFlow<androidx.media3.common.text.CueGroup?> = MutableStateFlow(null)
        override val downloadSpeedBps: StateFlow<Long> = MutableStateFlow(0L)

        override fun attachSurface(surface: android.view.Surface?) = Unit
        override fun play(session: PlaybackSession) = Unit
        override fun togglePlayPause() = Unit
        override fun seekTo(positionMs: Long, mode: SeekMode) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun selectAudioTrack(selection: TrackSelection?) = Unit
        override fun selectSubtitleTrack(selection: TrackSelection?) = Unit
        override fun stop(): PlaybackProgress? = null
        override fun release() = Unit
    }

    private object NoOpLogger : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }
}
