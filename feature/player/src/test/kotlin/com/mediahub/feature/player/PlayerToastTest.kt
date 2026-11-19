package com.mediahub.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 审计 P6：播放页三个提示条（引擎切换 / 播放失败 / 音频不支持）必须共用 PlayerToast，
 * 统一圆角与语义色 token，不再各自手写背景/黄字。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerToastTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun infoToastRendersItsMessage() {
        composeRule.setContent {
            MaterialTheme { PlayerToast("正在切换兼容播放模式…", PlayerToastTone.INFO) }
        }
        composeRule.onNodeWithText("正在切换兼容播放模式…").assertIsDisplayed()
    }

    @Test
    fun warningToastRendersItsMessage() {
        composeRule.setContent {
            MaterialTheme { PlayerToast("当前音频无法解码输出", PlayerToastTone.WARNING) }
        }
        composeRule.onNodeWithText("当前音频无法解码输出").assertIsDisplayed()
    }

    @Test
    fun errorToastRendersItsMessage() {
        composeRule.setContent {
            MaterialTheme { PlayerToast("播放失败，请重试", PlayerToastTone.ERROR) }
        }
        composeRule.onNodeWithText("播放失败，请重试").assertIsDisplayed()
    }

    @Test
    fun allTonesShareOneRoundedCornerToken() {
        assertEquals(12f, playerToastStyle(PlayerToastTone.INFO).cornerRadiusDp)
        assertEquals(12f, playerToastStyle(PlayerToastTone.WARNING).cornerRadiusDp)
        assertEquals(12f, playerToastStyle(PlayerToastTone.ERROR).cornerRadiusDp)
    }

    @Test
    fun warningUsesSemanticAmberInsteadOfYellowLiteral() {
        val warning = playerToastStyle(PlayerToastTone.WARNING)
        assertEquals(Color(0xFFFFE082), warning.foreground)
        assertNotEquals(Color.Yellow, warning.foreground)
        assertNotEquals(Color.Black.copy(alpha = 0.72f), warning.background)
    }

    @Test
    fun errorUsesSemanticRedDifferentFromWarning() {
        val warning = playerToastStyle(PlayerToastTone.WARNING)
        val error = playerToastStyle(PlayerToastTone.ERROR)
        assertEquals(Color(0xFFFFB4AB), error.foreground)
        assertNotEquals(warning.foreground, error.foreground)
        assertNotEquals(warning.background, error.background)
    }
}
