package com.mediahub.core.database.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.PlayerVisualPreset
import com.mediahub.model.UserPreferences
import com.mediahub.model.VisualPerformanceMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserPreferencesStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: UserPreferencesStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(temporaryFolder.root, "user.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        store = UserPreferencesStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `empty store exposes product visual defaults`() = runBlocking {
        val preferences = store.flow.first()

        assertEquals(UserPreferences(), preferences)
        assertEquals(PlayerVisualEffectsPreferences.Default, preferences.playerVisualEffects)
        assertTrue(preferences.playerVisualEffects.isEffectivelyEnabled)
    }

    @Test
    fun `visual preferences survive a store round trip`() = runBlocking {
        val expected = PlayerVisualEffectsPreferences(
            enabled = true,
            preset = PlayerVisualPreset.LIQUID,
            intensity = 0.68f,
            followArtworkColors = false,
            audioReactive = false,
            performanceMode = VisualPerformanceMode.HIGH,
        )
        store.update { current ->
            current.copy(
                playbackEngineMode = PlaybackEngineMode.MPV,
                subtitleSizeSp = 24,
                playerVisualEffects = expected,
            )
        }

        val recreatedStore = UserPreferencesStore(dataStore)
        val restored = recreatedStore.flow.first()

        assertEquals(expected, restored.playerVisualEffects)
        assertEquals(PlaybackEngineMode.MPV, restored.playbackEngineMode)
        assertEquals(24, restored.subtitleSizeSp)
    }

    @Test
    fun `visual preferences survive a closed DataStore and a fresh disk reader`() = runBlocking {
        val expected = PlayerVisualEffectsPreferences.Default.copy(
            enabled = false,
            preset = PlayerVisualPreset.SPECTRUM,
            intensity = 0.67f,
            followArtworkColors = false,
            audioReactive = false,
            performanceMode = VisualPerformanceMode.BATTERY,
        )
        store.updatePlayerVisualEffects { expected }
        // Stop the original store scope before reopening its file: a new repository around the
        // same DataStore instance alone would only prove an in-memory round trip.
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(temporaryFolder.root, "user.preferences_pb") },
        )
        store = UserPreferencesStore(dataStore)
        assertEquals(expected, store.flow.first().playerVisualEffects)
    }

    @Test
    fun `unknown persisted enums fall back without discarding other values`() = runBlocking {
        dataStore.edit { prefs ->
            prefs[UserPreferencesStore.Keys.VISUAL_ENABLED] = false
            prefs[UserPreferencesStore.Keys.VISUAL_PRESET] = "FUTURE_HOLOGRAM"
            prefs[UserPreferencesStore.Keys.VISUAL_INTENSITY] = 0.61f
            prefs[UserPreferencesStore.Keys.VISUAL_FOLLOW_ARTWORK] = false
            prefs[UserPreferencesStore.Keys.VISUAL_AUDIO_REACTIVE] = false
            prefs[UserPreferencesStore.Keys.VISUAL_PERFORMANCE_MODE] = "ULTRA_FUTURE"
        }

        val visual = store.flow.first().playerVisualEffects

        assertFalse(visual.enabled)
        assertEquals(PlayerVisualPreset.AURORA, visual.preset)
        assertEquals(0.61f, visual.intensity, 0f)
        assertFalse(visual.followArtworkColors)
        assertFalse(visual.audioReactive)
        assertEquals(VisualPerformanceMode.AUTO, visual.performanceMode)
    }

    @Test
    fun `invalid intensity is normalized on read and write`() = runBlocking {
        dataStore.edit { prefs ->
            prefs[UserPreferencesStore.Keys.VISUAL_INTENSITY] = Float.NaN
        }
        assertEquals(
            PlayerVisualEffectsPreferences.DEFAULT_INTENSITY,
            store.flow.first().playerVisualEffects.intensity,
            0f,
        )

        store.updatePlayerVisualEffects { current -> current.copy(intensity = -10f) }
        assertEquals(
            PlayerVisualEffectsPreferences.MIN_INTENSITY,
            store.flow.first().playerVisualEffects.intensity,
            0f,
        )
        assertEquals(
            PlayerVisualEffectsPreferences.MIN_INTENSITY,
            dataStore.data.first()[UserPreferencesStore.Keys.VISUAL_INTENSITY],
        )

        store.updatePlayerVisualEffects { current -> current.copy(intensity = 10f) }
        assertEquals(
            PlayerVisualEffectsPreferences.MAX_INTENSITY,
            store.flow.first().playerVisualEffects.intensity,
            0f,
        )
    }

    @Test
    fun `reset removes only visual keys and preserves unrelated preferences`() = runBlocking {
        store.update { current ->
            current.copy(
                subtitleSizeSp = 27,
                autoLandscape = false,
                playerVisualEffects = PlayerVisualEffectsPreferences(
                    enabled = false,
                    preset = PlayerVisualPreset.SPECTRUM,
                    intensity = 0.9f,
                    performanceMode = VisualPerformanceMode.BATTERY,
                ),
            )
        }

        store.resetPlayerVisualEffects()

        val restored = store.flow.first()
        assertEquals(27, restored.subtitleSizeSp)
        assertFalse(restored.autoLandscape)
        assertEquals(PlayerVisualEffectsPreferences.Default, restored.playerVisualEffects)

        val raw = dataStore.data.first()
        assertNull(raw[UserPreferencesStore.Keys.VISUAL_ENABLED])
        assertNull(raw[UserPreferencesStore.Keys.VISUAL_PRESET])
        assertNull(raw[UserPreferencesStore.Keys.VISUAL_INTENSITY])
        assertNull(raw[UserPreferencesStore.Keys.VISUAL_FOLLOW_ARTWORK])
        assertNull(raw[UserPreferencesStore.Keys.VISUAL_AUDIO_REACTIVE])
        assertNull(raw[UserPreferencesStore.Keys.VISUAL_PERFORMANCE_MODE])
    }

    @Test
    fun `concurrent updates serialize without losing fields`() = runBlocking {
        val firstTransformEntered = CountDownLatch(1)
        val releaseFirstTransform = CountDownLatch(1)
        val secondCallStarted = CountDownLatch(1)

        val unrelatedUpdate = async(Dispatchers.IO) {
            store.update { current ->
                firstTransformEntered.countDown()
                check(releaseFirstTransform.await(5, TimeUnit.SECONDS))
                current.copy(subtitleSizeSp = 31, autoLandscape = false)
            }
        }
        assertTrue(firstTransformEntered.await(5, TimeUnit.SECONDS))

        val visualUpdate = async(Dispatchers.IO) {
            secondCallStarted.countDown()
            store.updatePlayerVisualEffects { current ->
                current.copy(
                    preset = PlayerVisualPreset.LIQUID,
                    intensity = 0.74f,
                    performanceMode = VisualPerformanceMode.BALANCED,
                )
            }
        }
        assertTrue(secondCallStarted.await(5, TimeUnit.SECONDS))
        releaseFirstTransform.countDown()

        unrelatedUpdate.await()
        visualUpdate.await()

        val restored = store.flow.first()
        assertEquals(31, restored.subtitleSizeSp)
        assertFalse(restored.autoLandscape)
        assertEquals(PlayerVisualPreset.LIQUID, restored.playerVisualEffects.preset)
        assertEquals(0.74f, restored.playerVisualEffects.intensity, 0f)
        assertEquals(
            VisualPerformanceMode.BALANCED,
            restored.playerVisualEffects.performanceMode,
        )
    }
}
