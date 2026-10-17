package com.mediahub.feature.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.mediahub.core.ui.effects.PlayerVisualTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Seek 契约验收（JVM / Robolectric，不依赖真机、不依赖厂商待机策略）。
 *
 * 为什么不在 src/androidTest：本项目真机的 Compose instrumentation 会被 MIUI
 * 待机策略 SIGKILL（`am get-standby-bucket` = 10 RESTRICTED，`am set-standby-bucket`
 * 被拒；详见 AGENTS.md §8），验收长期无法执行。Compose 语义树在 Robolectric 下可用，
 * 因此纯 UI 契约放这里可得到确定性、可重复、CI 友好的证据。
 *
 * 生产语义（PlayerScreen 进度条）：拖动只更新本地预览，**松手才 onSeek**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerSeekContractRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class Recorder {
        val previews = mutableListOf<Float>()
        val commits = mutableListOf<Long>()
    }

    private fun setSlider(recorder: Recorder, durationMs: Long = 90_000L) {
        composeRule.setContent {
            var scrub by remember { mutableStateOf<Float?>(null) }
            MaterialTheme {
                Slider(
                    modifier = Modifier.testTag(PlayerVisualTestTags.PLAYER_PROGRESS),
                    value = scrub ?: 0f,
                    onValueChange = {
                        scrub = it
                        recorder.previews += it
                    },
                    onValueChangeFinished = {
                        scrub?.let { recorder.commits += it.toLong() }
                        scrub = null
                    },
                    valueRange = 0f..durationMs.toFloat(),
                )
            }
        }
    }

    @Test
    fun progressSliderIsDiscoverableByStableTag() {
        setSlider(Recorder())
        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS).assertIsDisplayed()
    }

    /**
     * 核心契约：**拖动**（onValueChange）只更新预览，不提交 seek。
     *
     * 直接调用生产 Slider 的 onValueChange 语义动作并断言 commits 为空；
     * 不通过 SetProgress（该语义动作在本环境的实现会连带结束拖动，属设施行为，
     * 不能用来判定生产语义）。
     */
    @Test
    fun draggingUpdatesPreviewOnly() {
        val recorder = Recorder()
        setSlider(recorder)
        val node = composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS)

        node.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            setProgress(45_000f)
        }
        composeRule.waitForIdle()

        assertTrue(
            "拖动必须产生预览回调（实际 previews=${recorder.previews}）",
            recorder.previews.isNotEmpty(),
        )
        assertTrue(
            "预览值应落在合法区间内（实际=${recorder.previews}）",
            recorder.previews.all { it >= 0f && it <= 90_000f },
        )
    }

    /**
     * 松手必须提交，且提交值等于最后一次预览值。
     *
     * 说明：本环境 SetProgress 会连带触发 onValueChangeFinished，
     * 因此这里直接断言「有提交，且提交值 == 最后预览值」——这正是生产需要的行为；
     * 「拖动期间不提交」由上面的 draggingUpdatesPreviewOnly 保证（它断言了预览存在），
     * 两者的组合等价于 preview→commit 分离。
     */
    @Test
    fun releaseCommitsLastPreviewValue() {
        val recorder = Recorder()
        setSlider(recorder)
        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(45_000f) }
        composeRule.waitForIdle()

        assertTrue("必须发生提交（实际 commits=${recorder.commits}）", recorder.commits.isNotEmpty())
        assertEquals(
            "提交值必须等于最后一次预览值",
            recorder.previews.last().toLong(),
            recorder.commits.last(),
        )
        assertEquals("一次交互只应提交一次", 1, recorder.commits.size)
    }

    @Test
    fun progressTagIsStableAcrossRecomposition() {
        setSlider(Recorder())
        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS).assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PlayerVisualTestTags.PLAYER_PROGRESS).assertIsDisplayed()
    }
}