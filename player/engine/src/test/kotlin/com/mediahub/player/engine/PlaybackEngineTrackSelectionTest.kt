@file:OptIn(UnstableApi::class)

package com.mediahub.player.engine

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mediahub.core.logging.StdoutLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 音轨 / 字幕选择的生产路径回归（T0003 验收标准 3、4、5、6）。
 *
 * 断言**真实 selector 参数**（[DefaultTrackSelector.Parameters] 的 `overrides` /
 * `getRendererDisabled`），路径与生产一致：[TrackSelectionPlanner] → `Parameters.Builder`。
 *
 * 覆盖：
 * - 同组多轨逐轨展开，行地址精确到 `(groupIndex, trackIndex)`；
 * - 选择写出指向真实 `TrackGroup` 的 override，且不改动其他类型；
 * - 关闭字幕（null）禁用 renderer 并清 override，随后重新选择可恢复；
 * - 越界 / 负值 / 陈旧快照一律拒绝，无参数变更；
 * - 快照令牌单调递增，旧令牌不匹配新快照。
 *
 * 基线修复前在"同组第二轨"与"陈旧快照"断言上失败（当时固定 `trackIndex=0` 且无令牌校验）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEngineTrackSelectionTest {

    private fun newPlayer(context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setTrackSelector(DefaultTrackSelector(context))
            .build()

    private fun newEngine(scope: TestScope, player: ExoPlayer): PlaybackEngine = PlaybackEngine(
        player = player,
        headersHolder = PlaybackHeadersHolder(),
        logger = StdoutLogger(),
        scope = scope,
        speedMonitor = PlaybackSpeedMonitor(),
    )

    private fun format(mime: String, language: String? = null, label: String? = null): Format =
        Format.Builder().setSampleMimeType(mime).setLanguage(language).setLabel(label).build()

    /** 单轨 / 多轨 Media3 组（类型由 Format MIME 推导）。 */
    private fun mediaGroup(vararg formats: Format): TrackGroup = TrackGroup(*formats)

    private fun tracksGroup(group: TrackGroup, selectedIndices: Set<Int>): Tracks.Group =
        Tracks.Group(
            group,
            false,
            IntArray(group.length) { C.FORMAT_HANDLED },
            BooleanArray(group.length) { it in selectedIndices },
        )

    private fun builder(context: Context): DefaultTrackSelector.Parameters.Builder =
        DefaultTrackSelector.Parameters.Builder(context)

    /** 混排轨道：视频 + 双音频组 + 字幕组，其中音频 / 字幕各组内含 2 轨。 */
    private fun twoTrackPerTypeTracks(): Tracks = Tracks(
        listOf(
            tracksGroup(mediaGroup(format(MimeTypes.VIDEO_H264)), selectedIndices = setOf(0)),
            tracksGroup(
                mediaGroup(format(MimeTypes.AUDIO_AAC, "eng"), format(MimeTypes.AUDIO_AC3, "chi")),
                selectedIndices = setOf(1),
            ),
            tracksGroup(
                mediaGroup(format(MimeTypes.APPLICATION_SUBRIP, "eng"), format(MimeTypes.TEXT_VTT, "chi")),
                selectedIndices = setOf(1),
            ),
        ),
    )

    // ---- 1. 同组多轨：逐轨展开 + 地址精确 ----

    @Test
    fun `multi-track group expands every track with precise group and track index`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val player = newPlayer(context)
        val engine = newEngine(this, player)
        try {
            val mapped = TrackMapper.mapTracks(twoTrackPerTypeTracks())

            // 同组两轨都进入列表（基线只取第 0 轨 → 只有 1 条）
            assertEquals(2, mapped.audioTracks.size)
            assertEquals(2, mapped.subtitleTracks.size)
            // 行序号 0/1 → 地址 (groupIndex=0, trackIndex=0/1)
            assertEquals(TrackSelection(0, 0, mapped.snapshotToken), mapped.rowMap.audioFor(0))
            assertEquals(TrackSelection(0, 1, mapped.snapshotToken), mapped.rowMap.audioFor(1))
            assertEquals(TrackSelection(0, 0, mapped.snapshotToken), mapped.rowMap.subtitleFor(0))
            assertEquals(TrackSelection(0, 1, mapped.snapshotToken), mapped.rowMap.subtitleFor(1))
            // 选中态落在组内第 2 轨，不是第 0 轨（基线把整组 isSelected 写进第 0 行）
            assertEquals(TrackSelection(0, 1, mapped.snapshotToken), mapped.selectedAudio)
            assertEquals(TrackSelection(0, 1, mapped.snapshotToken), mapped.selectedSubtitle)
            assertTrue(mapped.audioTracks[1].isSelected)
            assertFalse(mapped.audioTracks[0].isSelected)
            assertTrue(mapped.subtitleTracks[1].isSelected)
            assertFalse(mapped.subtitleTracks[0].isSelected)
        } finally {
            engine.release()
        }
    }

    // ---- 2. 混排：groupIndex 按同类型内序号计数，不受视频组影响 ----

    @Test
    fun `group index counts within type and ignores video groups`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val player = newPlayer(context)
        val engine = newEngine(this, player)
        try {
            val mapped = TrackMapper.mapTracks(
                Tracks(
                    listOf(
                        tracksGroup(mediaGroup(format(MimeTypes.VIDEO_H264)), setOf(0)),
                        tracksGroup(mediaGroup(format(MimeTypes.AUDIO_AAC)), setOf(0)),
                        tracksGroup(mediaGroup(format(MimeTypes.AUDIO_AC3)), emptySet()),
                        tracksGroup(mediaGroup(format(MimeTypes.APPLICATION_SUBRIP)), setOf(0)),
                    ),
                ),
            )
            assertEquals(2, mapped.audioTracks.size)
            assertEquals(1, mapped.subtitleTracks.size)
            assertEquals(TrackSelection(0, 0, mapped.snapshotToken), mapped.rowMap.audioFor(0))
            assertEquals(TrackSelection(1, 0, mapped.snapshotToken), mapped.rowMap.audioFor(1))
            assertEquals(TrackSelection(0, 0, mapped.snapshotToken), mapped.rowMap.subtitleFor(0))
        } finally {
            engine.release()
        }
    }

    // ---- 3. 生产路径写出真实 override ----

    @Test
    fun `selection writes a real override for exact group and track`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val player = newPlayer(context)
        val engine = newEngine(this, player)
        try {
            val group0 = mediaGroup(format(MimeTypes.AUDIO_AAC))
            val group1 = mediaGroup(format(MimeTypes.AUDIO_AC3), format(MimeTypes.AUDIO_E_AC3_JOC))
            val groups = TrackGroupArray(group0, group1)

            val plan = TrackSelectionPlanner.plan(
                rendererIndex = 1,
                groups = groups,
                selection = TrackSelection(groupIndex = 1, trackIndex = 1, snapshotToken = 7L),
                currentSnapshotToken = 7L,
            )
            assertTrue(plan is TrackSelectionPlanner.Plan.Override)
            val overridePlan = plan as TrackSelectionPlanner.Plan.Override
            assertEquals(1, overridePlan.rendererIndex)
            assertEquals(1, overridePlan.groupIndex)
            assertEquals(1, overridePlan.trackIndex)

            val params = TrackSelectionPlanner.apply(builder(context), overridePlan).build()
            // 生产路径经 setSelectionOverride(rendererIndex, groups, ...)：按 renderer + 组序列读取
            assertTrue("必须写出该 renderer 的 override", params.hasSelectionOverride(1, groups))
            val written = params.getSelectionOverride(1, groups)
            assertTrue("override 目标必须是组内第 2 轨", written != null)
            assertEquals(1, written!!.groupIndex)
            assertTrue(written.containsTrack(1))
            assertFalse(params.getRendererDisabled(1))
        } finally {
            engine.release()
        }
    }

    // ---- 4. 关闭字幕：禁用 renderer + 清 override，随后选择可恢复 ----

    @Test
    fun `null selection disables the renderer and later selection re-enables it`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val player = newPlayer(context)
        val engine = newEngine(this, player)
        try {
            val group = mediaGroup(format(MimeTypes.APPLICATION_SUBRIP))
            val groups = TrackGroupArray(group)

            val disable = TrackSelectionPlanner.plan(
                rendererIndex = 2,
                groups = groups,
                selection = null,
                currentSnapshotToken = 1L,
            )
            assertTrue(disable is TrackSelectionPlanner.Plan.Disable)
            val disabledParams = TrackSelectionPlanner.apply(builder(context), disable!!).build()
            assertTrue("关闭字幕必须禁用该 renderer", disabledParams.getRendererDisabled(2))
            assertFalse(
                "关闭字幕必须清掉该 renderer 的显式 override",
                disabledParams.hasSelectionOverride(2, groups),
            )

            // 随后选择有效字幕：解除禁用并写入 override
            val enable = TrackSelectionPlanner.plan(
                rendererIndex = 2,
                groups = groups,
                selection = TrackSelection(0, 0, snapshotToken = 1L),
                currentSnapshotToken = 1L,
            )
            assertTrue(enable is TrackSelectionPlanner.Plan.Override)
            val enabledParams = TrackSelectionPlanner.apply(builder(context), enable!!).build()
            assertFalse(enabledParams.getRendererDisabled(2))
            assertTrue(enabledParams.hasSelectionOverride(2, groups))
        } finally {
            engine.release()
        }
    }

    // ---- 5. 越界 / 负值 / 陈旧快照 一律拒绝 ----

    @Test
    fun `out of range negative and stale addresses are rejected`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val player = newPlayer(context)
        val engine = newEngine(this, player)
        try {
            val groups = TrackGroupArray(mediaGroup(format(MimeTypes.AUDIO_AAC)))

            assertNull("组号越界", TrackSelectionPlanner.plan(1, groups, TrackSelection(5, 0, 1L), 1L))
            assertNull("轨号越界", TrackSelectionPlanner.plan(1, groups, TrackSelection(0, 3, 1L), 1L))
            assertNull("组号负值", TrackSelectionPlanner.plan(1, groups, TrackSelection(-1, 0, 1L), 1L))
            assertNull("轨号负值", TrackSelectionPlanner.plan(1, groups, TrackSelection(0, -1, 1L), 1L))
            assertNull("陈旧快照令牌", TrackSelectionPlanner.plan(1, groups, TrackSelection(0, 0, 1L), 9L))
            // 令牌为 null 的兼容地址允许通过
            assertTrue(
                TrackSelectionPlanner.plan(1, groups, TrackSelection(0, 0, null), 9L)
                    is TrackSelectionPlanner.Plan.Override,
            )

            // 引擎在无 MappedTrackInfo 时不抛异常、不改参数
            val before = player.trackSelectionParameters
            engine.selectAudioTrack(TrackSelection(0, 0))
            engine.selectSubtitleTrack(TrackSelection(99, 99))
            engine.selectSubtitleTrack(null)
            assertNull(engine.uiState.value.error)
            assertEquals(before.overrides, player.trackSelectionParameters.overrides)
        } finally {
            engine.release()
        }
    }

    // ---- 6. 快照令牌单调递增 ----

    @Test
    fun `snapshot token distinguishes successive snapshots`() = runTest {
        val first = TrackMapper.mapTracks(Tracks(emptyList()))
        val second = TrackMapper.mapTracks(Tracks(emptyList()))
        assertNotEquals(first.snapshotToken, second.snapshotToken)
        assertTrue(first.rowMap.matches(first.snapshotToken))
        assertFalse(first.rowMap.matches(second.snapshotToken))
        assertNotEquals(first.rowMap.audioFor(0)?.snapshotToken, second.snapshotToken)
    }

    // ---- 7. 空映射查询返回 null ----

    @Test
    fun `empty row map yields null address for any row`() {
        assertNull(TrackRowMap.EMPTY.audioFor(0))
        assertNull(TrackRowMap.EMPTY.subtitleFor(5))
        assertNull(TrackRowMap.EMPTY.subtitleFor(-1))
    }
}
