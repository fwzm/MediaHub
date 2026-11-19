package com.mediahub.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.mediahub.feature.player.gesture.GestureLevelPreview
import com.mediahub.feature.player.gesture.GestureSeekPreview
import com.mediahub.feature.player.gesture.GestureSpeedPreview
import com.mediahub.feature.player.gesture.PlayerLevelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UI 视觉审计 P7：seek / speed / level 三个手势指示器共用同一容器皮肤。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerGestureIndicatorsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allGestureIndicatorsShareTheSameSurfaceContainer() {
        composeRule.setContent {
            MaterialTheme {
                Column {
                    GestureSeekIndicator(GestureSeekPreview(targetPositionMs = 65_000L, deltaMs = -5_000L))
                    GestureSpeedIndicator(GestureSpeedPreview(speed = 2f, isRewind = false))
                    GestureLevelIndicator(GestureLevelPreview(kind = PlayerLevelKind.VOLUME, fraction = 0.42f))
                }
            }
        }
        composeRule.onAllNodesWithTag(PLAYER_GESTURE_INDICATOR_TAG).assertCountEquals(3)
    }

    @Test
    fun seekIndicatorShowsDeltaAndTargetTime() {
        composeRule.setContent {
            MaterialTheme {
                GestureSeekIndicator(GestureSeekPreview(targetPositionMs = 65_000L, deltaMs = -5_000L))
            }
        }
        composeRule.onNodeWithText("-0:05", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("1:05", substring = true).assertIsDisplayed()
    }

    @Test
    fun speedIndicatorShowsTemporarySpeed() {
        composeRule.setContent {
            MaterialTheme {
                GestureSpeedIndicator(GestureSpeedPreview(speed = 2f, isRewind = false))
            }
        }
        // Speed wording is localized (zh "2.00× 倍速中" / en "Playing at 2.00×"); assert the shared numeric core.
        composeRule.onNodeWithText("2.00×", substring = true).assertIsDisplayed()
    }

    @Test
    fun sharedStyleTokenReplacesTheThreeAuditSkins() {
        val style = playerGestureIndicatorStyle()
        assertEquals("shared translucent scrim alpha", 0.72f, style.background.alpha, 0.005f)
        assertEquals("shared corner radius", 16f, style.cornerRadiusDp, 0f)
        assertEquals("shared horizontal padding", 20f, style.horizontalPaddingDp, 0f)
        assertEquals("shared vertical padding", 12f, style.verticalPaddingDp, 0f)
        assertNotEquals("seek/speed old 0.65 skin must not survive", 0.65f, style.background.alpha, 0.0001f)
        assertNotEquals("level old 0.60 skin must not survive", 0.60f, style.background.alpha, 0.0001f)
        assertNotEquals("level old 28dp capsule must not survive", 28f, style.cornerRadiusDp, 0f)
    }
}
