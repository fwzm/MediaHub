package com.mediahub.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 审计 P10（底部信息行）契约：速度/电量不再是两端对齐的整行，而是右下角一个
 * 次级信息簇 —— 标签由 [playerSecondaryInfoLabel] 合并，[PlayerSecondaryInfo]
 * 负责把它贴在宿主右下角并在两者都为空时完全不占位。
 *
 * 测试直接组合生产 composable，因此把信息簇改回 SpaceBetween 整行、或让它
 * 左对齐/永远渲染占位时，这里必须 RED。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSecondaryInfoTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun labelJoinsSpeedAndBatteryWithOneSeparator() {
        assertEquals("1.2 MB/s · 电量 42%", playerSecondaryInfoLabel("1.2 MB/s", "电量 42%"))
    }

    @Test
    fun labelKeepsOnlyTheFieldsThatExist() {
        assertEquals("1.2 MB/s", playerSecondaryInfoLabel("1.2 MB/s", null))
        assertEquals("电量 42%", playerSecondaryInfoLabel("", "电量 42%"))
        assertNull(playerSecondaryInfoLabel("", null))
        assertNull(playerSecondaryInfoLabel("  ", " "))
    }

    @Test
    fun clusterSitsAtTheBottomEndOfItsHost() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).fillMaxWidth()) {
                    PlayerSecondaryInfo(
                        speedText = "1.2 MB/s",
                        batteryText = "电量 42%",
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }
        composeRule.onNodeWithTag(PLAYER_SECONDARY_INFO_TAG).assertIsDisplayed()
        val bounds = composeRule.onNodeWithTag(PLAYER_SECONDARY_INFO_TAG).getUnclippedBoundsInRoot()
        assertTrue("cluster must be pushed to the right edge, was right=" + bounds.right.value, bounds.right.value >= 300f)
        assertTrue("cluster must not stretch from the left edge, was left=" + bounds.left.value, bounds.left.value > 0f)
    }

    @Test
    fun clusterRendersNothingWhenBothFieldsAreEmpty() {
        composeRule.setContent {
            MaterialTheme {
                PlayerSecondaryInfo(speedText = "", batteryText = null)
            }
        }
        composeRule.onNodeWithTag(PLAYER_SECONDARY_INFO_TAG).assertDoesNotExist()
    }
}
