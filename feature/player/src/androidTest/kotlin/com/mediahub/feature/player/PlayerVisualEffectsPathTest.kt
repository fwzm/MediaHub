package com.mediahub.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediahub.core.ui.effects.PlayerVisualTestTags
import com.mediahub.core.ui.effects.SpectrumProvider
import com.mediahub.core.ui.effects.VisualPalette
import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.PlayerVisualPreset
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerVisualEffectsPathTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun playerEntryOpensSettingsAndPresetOffPathUpdatesProductState() {
        val latest = AtomicReference(PlayerVisualEffectsPreferences())
        composeRule.setContent {
            var open by remember { mutableStateOf(false) }
            var preferences by remember { mutableStateOf(PlayerVisualEffectsPreferences()) }
            MaterialTheme {
                if (open) {
                    PlayerVisualEffectsSheet(
                        preferences = preferences,
                        previewPalette = VisualPalette.Fallback,
                        spectrum = SpectrumProvider.Noop,
                        audioReactiveAvailable = false,
                        showAudioAccessAction = false,
                        audioAccessActionIsRetry = false,
                        lifecycleStarted = false,
                        onPreferencesChanged = {
                            preferences = it
                            latest.set(it)
                        },
                        onRestoreDefaults = {
                            preferences = PlayerVisualEffectsPreferences.Default
                            latest.set(preferences)
                        },
                        onRequestAudioAccess = {},
                        onDismiss = { open = false },
                    )
                } else {
                    PlayerVisualEffectsEntry(onClick = { open = true })
                }
            }
        }

        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_ENTRY)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(PlayerVisualTestTags.SETTINGS).assertIsDisplayed()

        composeRule.onNodeWithTag(PlayerVisualTestTags.PRESET_LIQUID)
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertTrue(latest.get().enabled)
            assertEquals(PlayerVisualPreset.LIQUID, latest.get().preset)
        }

        composeRule.onNodeWithTag(PlayerVisualTestTags.PRESET_OFF)
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertFalse(latest.get().enabled)
            assertEquals(PlayerVisualPreset.LIQUID, latest.get().preset)
        }
    }
}
