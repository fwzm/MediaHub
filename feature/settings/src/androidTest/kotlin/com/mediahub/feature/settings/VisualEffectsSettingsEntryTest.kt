package com.mediahub.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediahub.core.database.prefs.UserPreferencesStore
import com.mediahub.core.ui.effects.PlayerVisualTestTags
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.PlayerVisualPreset
import com.mediahub.model.UserPreferences
import com.mediahub.model.VisualPerformanceMode
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
class VisualEffectsSettingsEntryTest {
    @get:Rule
    val composeRule = createComposeRule()
    private val screenVisible = mutableStateOf(false)
    private val viewModelStores = mutableListOf<ViewModelStore>()
    private var originalPreferences: UserPreferences? = null
    private var preferencesStore: UserPreferencesStore? = null

    @After
    fun restoreTestState() {
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            screenVisible.value = false
            viewModelStores.forEach(ViewModelStore::clear)
        }
        val original = originalPreferences ?: return
        runBlocking { preferencesStore?.update { original } }
    }

    @Test
    fun realSettingsRoutePersistsAndReloadsVisualPreferencesThenRestoresDefaults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = UserPreferencesStore(context)
        preferencesStore = repository
        originalPreferences = runBlocking { repository.flow.first() }
        runBlocking {
            repository.update {
                UserPreferences(
                    autoLandscape = false,
                    immersiveBars = false,
                    playerVisualEffects = PlayerVisualEffectsPreferences.Default.copy(enabled = false),
                )
            }
        }
        val viewModel = newViewModel(repository)
        val currentViewModel = mutableStateOf(viewModel)
        screenVisible.value = true
        composeRule.setContent {
            MaterialTheme {
                if (screenVisible.value) {
                    // Use the real lifecycle-aware shared preview; no previewRunning override.
                    SettingsRoute(onBack = {}, viewModel = currentViewModel.value)
                }
            }
        }
        awaitVisual(viewModel) { !it.enabled }
        composeRule.onNodeWithTag(PlayerVisualTestTags.SETTINGS_ENTRY)
            .performScrollToWithClock(composeRule)
            .assertIsDisplayed()
        captureVisualEvidence(composeRule, "settings-entry")
        openSheet()
        // Freeze only after the sheet entrance is visible. Preset changes then drive actual
        // preview frames explicitly, avoiding an infinite renderer-idle wait.
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag(PlayerVisualTestTags.PRESET_LIQUID).performScrollToWithClock(composeRule).performClick()
        awaitVisual(viewModel) { it.enabled && it.preset == PlayerVisualPreset.LIQUID }
        assertTrue(persistedVisual().enabled)
        assertEquals(PlayerVisualPreset.LIQUID, persistedVisual().preset)

        composeRule.onNodeWithTag(PlayerVisualTestTags.INTENSITY)
            .performScrollToWithClock(composeRule)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.55f) }
        awaitVisual(viewModel) { it.intensity == 0.55f }
        composeRule.onNodeWithTag(PlayerVisualTestTags.performance(VisualPerformanceMode.BATTERY))
            .performScrollToWithClock(composeRule)
            .performClick()
        awaitVisual(viewModel) { it.performanceMode == VisualPerformanceMode.BATTERY }
        composeRule.onNodeWithTag(PlayerVisualTestTags.PREVIEW).performScrollToWithClock(composeRule)
        captureVisualEvidence(composeRule, "settings-sheet")

        composeRule.onNodeWithTag(PlayerVisualTestTags.PRESET_OFF).performScrollToWithClock(composeRule).performClick()
        awaitVisual(viewModel) { !it.enabled && it.preset == PlayerVisualPreset.LIQUID }
        val storedOff = persistedVisual()
        assertFalse(storedOff.enabled)
        assertEquals(0.55f, storedOff.intensity, 0.001f)
        assertEquals(VisualPerformanceMode.BATTERY, storedOff.performanceMode)
        closeSheet()

        composeRule.runOnUiThread { screenVisible.value = false }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag(PlayerVisualTestTags.SETTINGS_ENTRY).assertDoesNotExist()
        composeRule.runOnUiThread { viewModelStores.first().clear() }
        val reopenedRepository = UserPreferencesStore(context)
        assertEquals(storedOff, runBlocking { reopenedRepository.flow.first() }.playerVisualEffects)
        val reopenedViewModel = newViewModel(reopenedRepository)
        composeRule.runOnUiThread {
            currentViewModel.value = reopenedViewModel
            screenVisible.value = true
        }
        awaitVisual(reopenedViewModel) { it == storedOff }
        openSheet()
        composeRule.onNodeWithTag(PlayerVisualTestTags.PRESET_OFF)
            .performScrollToWithClock(composeRule)
            .assertIsSelected()
        assertEquals(storedOff, persistedVisual())

        composeRule.onNodeWithTag(PlayerVisualTestTags.RESTORE_DEFAULTS).performScrollToWithClock(composeRule).performClick()
        awaitVisual(reopenedViewModel) { it == PlayerVisualEffectsPreferences.Default }
        assertEquals(PlayerVisualEffectsPreferences.Default, persistedVisual())
        // Resetting visual keys must preserve unrelated orientation preferences.
        assertFalse(runBlocking { repository.flow.first() }.autoLandscape)
        assertFalse(runBlocking { repository.flow.first() }.immersiveBars)
    }

    private fun newViewModel(repository: UserPreferencesStore): SettingsViewModel =
        SettingsViewModel(repository).also { viewModel ->
            viewModelStores += ViewModelStore().apply { put("settings", viewModel) }
        }

    private fun openSheet() {
        composeRule.onNodeWithTag(PlayerVisualTestTags.SETTINGS_ENTRY).performScrollToWithClock(composeRule).performClick()
        // A visible sliver is not a completed entrance. Await the actual expanded geometry.
        expandVisualSheetWithClock(composeRule)
    }

    private fun closeSheet() {
        // Back first collapses an expanded Material3 sheet. Dismiss is its actual
        // accessibility action and runs the production hide animation plus dismiss callback.
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
    }

    private fun awaitVisual(viewModel: SettingsViewModel, predicate: (PlayerVisualEffectsPreferences) -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.mainClock.advanceTimeByFrame()
            predicate(viewModel.preferences.value.playerVisualEffects)
        }
        composeRule.mainClock.advanceTimeBy(1_000)
    }

    private fun persistedVisual(): PlayerVisualEffectsPreferences = runBlocking {
        checkNotNull(preferencesStore).flow.first().playerVisualEffects
    }
}
