package com.mediahub.player.engine

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.mediahub.core.logging.StdoutLogger
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 引擎选轨契约回归（T0002 / ADR-041 纠正 ADR-032）。
 *
 * 与 [TrackMapperTest] 的分工：mapper 测试只证明"映射产出的序号"，本测试证明
 * **引擎把该序号用在正确的 TrackGroup 上**——通过真正的 [PlaybackEngine.selectAudioTrack] /
 * [PlaybackEngine.selectSubtitleTrack] 入口，检查真实 [DefaultTrackSelector] 的
 * `TrackSelectionParameters.overrides`（键为真实 [TrackGroup]）与 `disabledTrackTypes`。
 *
 * 输入 [Tracks] 由测试夹具合成（测试替身只提供轨道输入与播放器外围接口，不复制
 * 待修的选择逻辑）；不加载真实媒体、不访问网络、不触碰 mpv。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEngineTrackSelectionTest {

    // ---- 夹具 ----

    private class Fixture(val tracks: Tracks) {
        private val groups: List<Tracks.Group> = tracks.groups

        /** 目标类型过滤后的真实组序号；与 TrackMapper 的序号契约同源。 */
        fun ordinalOf(type: Int, groupId: String): Int {
            val ordinals = groups.filter { it.type == type }
            val index = ordinals.indexOfFirst { it.mediaTrackGroup.id == groupId }
            check(index >= 0) { "夹具中不存在 $groupId" }
            return index
        }

        fun assertOrdinals(vararg expected: Pair<Int, String>) {
            expected.forEach { (ordinal, groupId) ->
                assertEquals("序号 $ordinal 必须落在 $groupId", ordinal, ordinalOf(groups.first { it.mediaTrackGroup.id == groupId }.type, groupId))
            }
        }

        fun trackGroup(groupId: String): TrackGroup =
            groups.first { it.mediaTrackGroup.id == groupId }.mediaTrackGroup

        fun group(groupId: String): Tracks.Group =
            groups.first { it.mediaTrackGroup.id == groupId }
    }

    private fun groupOf(
        type: Int,
        groupId: String,
        mime: String,
        trackCount: Int = 1,
        supported: Boolean = true,
        selected: Boolean = false,
        language: String? = null,
    ): Tracks.Group {
        val formats = Array(trackCount) { index ->
            Format.Builder()
                .setId("$groupId#$index")
                .setSampleMimeType(mime)
                .setLanguage(language)
                .build()
        }
        val trackGroup = TrackGroup(groupId, *formats)
        check(trackGroup.type == type) {
            "夹具 mime=$mime 推导出的轨道类型 ${trackGroup.type} 与期望 $type 不符"
        }
        return Tracks.Group(
            trackGroup,
            false,
            IntArray(trackCount) { if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE },
            BooleanArray(trackCount) { selected },
        )
    }

    /** 混排夹具：video、audioA、textA、audioB、textB。 */
    private fun mixedFixture(): Fixture = Fixture(
        Tracks(
            listOf(
                groupOf(C.TRACK_TYPE_VIDEO, VIDEO, "video/avc"),
                groupOf(C.TRACK_TYPE_AUDIO, AUDIO_A, "audio/mp4a-latm", language = "en", selected = true),
                groupOf(C.TRACK_TYPE_TEXT, TEXT_A, "application/x-subrip", language = "en"),
                groupOf(C.TRACK_TYPE_AUDIO, AUDIO_B, "audio/eac3", language = "zh"),
                groupOf(C.TRACK_TYPE_TEXT, TEXT_B, "application/x-subrip", language = "zh"),
            ),
        ),
    )

    /** 目标前方插入 unsupported 组：video、unsupportedAudio、audioA、textA、textB。 */
    private fun unsupportedFirstFixture(): Fixture = Fixture(
        Tracks(
            listOf(
                groupOf(C.TRACK_TYPE_VIDEO, VIDEO, "video/avc"),
                groupOf(C.TRACK_TYPE_AUDIO, AUDIO_UNSUPPORTED, "audio/vnd.dts.hd", supported = false),
                groupOf(C.TRACK_TYPE_AUDIO, AUDIO_A, "audio/mp4a-latm", language = "en"),
                groupOf(C.TRACK_TYPE_TEXT, TEXT_A, "application/x-subrip", language = "en"),
                groupOf(C.TRACK_TYPE_TEXT, TEXT_B, "application/x-subrip", language = "zh"),
            ),
        ),
    )

    private fun newSelector(scope: TestScope, tracks: () -> Tracks): Pair<PlaybackEngine, DefaultTrackSelector> {
        val context: Context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).build()
        val selector = requireNotNull(player.trackSelector as? DefaultTrackSelector)
        val engine = PlaybackEngine(
            player = player,
            headersHolder = PlaybackHeadersHolder(),
            logger = StdoutLogger(),
            scope = scope,
            speedMonitor = PlaybackSpeedMonitor(),
            tracksProvider = tracks,
        )
        return engine to selector
    }

    private fun overridesOf(selector: DefaultTrackSelector) = selector.parameters.overrides
    private fun disabledTypesOf(selector: DefaultTrackSelector) = selector.parameters.disabledTrackTypes.toSet()

    private fun assertOverride(
        selector: DefaultTrackSelector,
        expectedGroupId: String,
        expectedTrackIndices: List<Int>,
        fixture: Fixture,
    ) {
        val override = overridesOf(selector)[fixture.trackGroup(expectedGroupId)]
        assertNotNull("必须为 $expectedGroupId 写入 override", override)
        assertEquals(
            "$expectedGroupId 的 trackIndices",
            expectedTrackIndices,
            override!!.trackIndices.toList(),
        )
    }

    private fun assertOnlyOverride(selector: DefaultTrackSelector, expectedGroupIds: Set<String>, fixture: Fixture) {
        assertEquals(
            "override 集合必须恰好命中目标组",
            expectedGroupIds,
            overridesOf(selector).keys.map { it.id }.toSet(),
        )
        overridesOf(selector).keys.forEach { group ->
            assertNotNull("override 的 TrackGroup 必须来自当前 Tracks 快照", fixture.trackGroup(group.id))
        }
    }

    // ---- 用例 ----

    @Test
    fun `engine audio and subtitle ordinals hit the same groups as TrackMapper`() = runTest {
        val fixture = mixedFixture()
        val (engine, selector) = newSelector(this, fixture::tracks)
        try {
            // 序号契约自证：audioA=0 / audioB=1 / textA=0 / textB=1
            assertEquals(0, fixture.ordinalOf(C.TRACK_TYPE_AUDIO, AUDIO_A))
            assertEquals(1, fixture.ordinalOf(C.TRACK_TYPE_AUDIO, AUDIO_B))
            assertEquals(0, fixture.ordinalOf(C.TRACK_TYPE_TEXT, TEXT_A))
            assertEquals(1, fixture.ordinalOf(C.TRACK_TYPE_TEXT, TEXT_B))

            engine.selectAudioTrack(TrackSelection(fixture.ordinalOf(C.TRACK_TYPE_AUDIO, AUDIO_B), 0))
            assertOnlyOverride(selector, setOf(AUDIO_B), fixture)
            assertOverride(selector, AUDIO_B, listOf(0), fixture)

            engine.selectSubtitleTrack(TrackSelection(fixture.ordinalOf(C.TRACK_TYPE_TEXT, TEXT_B), 0))
            assertOnlyOverride(selector, setOf(AUDIO_B, TEXT_B), fixture)
            assertOverride(selector, AUDIO_B, listOf(0), fixture)
            assertOverride(selector, TEXT_B, listOf(0), fixture)

            // UI 回传的序号（TrackMapper 产出）与引擎命中的组一致
            val mapped = TrackMapper.mapTracks(fixture.tracks)
            val uiAudioOrdinal = mapped.audioTracks.single { it.language == "zh" }.index
            assertEquals("TrackMapper 的 audioB 序号必须是 1", 1, uiAudioOrdinal)
            engine.selectAudioTrack(TrackSelection(uiAudioOrdinal, 0))
            assertOverride(selector, AUDIO_B, listOf(0), fixture)
        } finally {
            engine.release()
        }
    }

    @Test
    fun `unsupported group in front does not shift the engine ordinal mapping`() = runTest {
        val fixture = unsupportedFirstFixture()
        val (engine, selector) = newSelector(this, fixture::tracks)
        try {
            // 不额外过滤 unsupported：unsupportedAudio=0、audioA=1
            assertEquals(0, fixture.ordinalOf(C.TRACK_TYPE_AUDIO, AUDIO_UNSUPPORTED))
            assertEquals(1, fixture.ordinalOf(C.TRACK_TYPE_AUDIO, AUDIO_A))
            assertFalse(fixture.group(AUDIO_UNSUPPORTED).isTrackSupported(0))

            engine.selectAudioTrack(TrackSelection(fixture.ordinalOf(C.TRACK_TYPE_AUDIO, AUDIO_A), 0))
            assertOnlyOverride(selector, setOf(AUDIO_A), fixture)
            assertOverride(selector, AUDIO_A, listOf(0), fixture)

            engine.selectSubtitleTrack(TrackSelection(fixture.ordinalOf(C.TRACK_TYPE_TEXT, TEXT_B), 0))
            assertOnlyOverride(selector, setOf(AUDIO_A, TEXT_B), fixture)

            // TrackMapper 与引擎对同一夹具给出一致序号
            val mapped = TrackMapper.mapTracks(fixture.tracks)
            assertEquals(
                "mapper 与引擎的 audioA 序号必须一致",
                fixture.ordinalOf(C.TRACK_TYPE_AUDIO, AUDIO_A),
                mapped.audioTracks.single { it.language == "en" }.index,
            )
            assertEquals(2, mapped.audioTracks.size)
        } finally {
            engine.release()
        }
    }

    @Test
    fun `closing subtitles clears text override and disables text while preserving audio`() = runTest {
        val fixture = mixedFixture()
        val (engine, selector) = newSelector(this, fixture::tracks)
        try {
            engine.selectAudioTrack(TrackSelection(1, 0))
            engine.selectSubtitleTrack(TrackSelection(1, 0))
            assertOnlyOverride(selector, setOf(AUDIO_B, TEXT_B), fixture)

            // 关闭字幕：清除 TEXT 显式 override 并禁用 TEXT
            engine.selectSubtitleTrack(null)
            assertOnlyOverride(selector, setOf(AUDIO_B), fixture)
            assertEquals(setOf(C.TRACK_TYPE_TEXT), disabledTypesOf(selector))

            // 重开字幕：解除 TEXT 禁用并替换旧 TEXT override，音频参数不变
            engine.selectSubtitleTrack(TrackSelection(0, 0))
            assertOnlyOverride(selector, setOf(AUDIO_B, TEXT_A), fixture)
            assertOverride(selector, AUDIO_B, listOf(0), fixture)
            assertTrue("重开后 TEXT 不得仍被禁用", disabledTypesOf(selector).isEmpty())

            // 再次切换字幕：替换而非叠加
            engine.selectSubtitleTrack(TrackSelection(1, 0))
            assertOnlyOverride(selector, setOf(AUDIO_B, TEXT_B), fixture)

            // 关闭字幕后切音频：字幕禁用状态保留，音频 override 更新
            engine.selectSubtitleTrack(null)
            engine.selectAudioTrack(TrackSelection(0, 0))
            assertOnlyOverride(selector, setOf(AUDIO_A), fixture)
            assertEquals("音频切换不得解除字幕禁用", setOf(C.TRACK_TYPE_TEXT), disabledTypesOf(selector))

            // 字幕选择同样保留音频 override
            engine.selectSubtitleTrack(TrackSelection(0, 0))
            assertOnlyOverride(selector, setOf(AUDIO_A, TEXT_A), fixture)
            assertEquals("字幕重开不得残留音频禁用", emptySet<Int>(), disabledTypesOf(selector))
        } finally {
            engine.release()
        }
    }

    @Test
    fun `invalid selections leave every parameter untouched and never throw`() = runTest {
        val fixture = mixedFixture()
        val (engine, selector) = newSelector(this, fixture::tracks)
        try {
            val before = selector.parameters

            engine.selectAudioTrack(TrackSelection(-1, 0))
            engine.selectAudioTrack(TrackSelection(99, 0))
            engine.selectAudioTrack(TrackSelection(0, -1))
            engine.selectAudioTrack(TrackSelection(0, 5))
            engine.selectSubtitleTrack(TrackSelection(-3, 0))
            engine.selectSubtitleTrack(TrackSelection(7, 0))
            engine.selectSubtitleTrack(TrackSelection(1, 4))

            assertEquals("无效选择必须保持全部参数原样", before, selector.parameters)
            assertTrue(overridesOf(selector).isEmpty())
            assertTrue(disabledTypesOf(selector).isEmpty())
        } finally {
            engine.release()
        }
    }

    @Test
    fun `empty tracks and missing target type stay safe`() = runTest {
        val empty = Fixture(Tracks.EMPTY)
        val (engine, selector) = newSelector(this, empty::tracks)
        try {
            // 非 null 请求：无轨道、无对应 renderer → 无副作用
            engine.selectAudioTrack(TrackSelection(0, 0))
            engine.selectSubtitleTrack(TrackSelection(0, 0))
            assertTrue(overridesOf(selector).isEmpty())
            assertTrue(disabledTypesOf(selector).isEmpty())

            // null 关闭命令：即使无任何轨道也要能安全下发类型禁用策略
            engine.selectSubtitleTrack(null)
            assertEquals(setOf(C.TRACK_TYPE_TEXT), disabledTypesOf(selector))
            assertTrue(overridesOf(selector).isEmpty())

            engine.selectAudioTrack(null)
            assertEquals(setOf(C.TRACK_TYPE_TEXT, C.TRACK_TYPE_AUDIO), disabledTypesOf(selector))
            assertTrue(overridesOf(selector).isEmpty())
        } finally {
            engine.release()
        }

        // 只有视频、无 TEXT 组：字幕请求无副作用
        val videoOnly = Fixture(Tracks(listOf(groupOf(C.TRACK_TYPE_VIDEO, VIDEO, "video/avc"))))
        val (engine2, selector2) = newSelector(this, videoOnly::tracks)
        try {
            engine2.selectSubtitleTrack(TrackSelection(0, 0))
            assertTrue(overridesOf(selector2).isEmpty())
            assertTrue(disabledTypesOf(selector2).isEmpty())
        } finally {
            engine2.release()
        }
    }

    @Test
    fun `non zero track index is written verbatim`() = runTest {
        val fixture = Fixture(
            Tracks(
                listOf(
                    groupOf(C.TRACK_TYPE_AUDIO, AUDIO_A, "audio/mp4a-latm", trackCount = 3, language = "eng"),
                    groupOf(C.TRACK_TYPE_TEXT, TEXT_A, "application/x-subrip", trackCount = 2),
                ),
            ),
        )
        val (engine, selector) = newSelector(this, fixture::tracks)
        try {
            engine.selectAudioTrack(TrackSelection(0, 2))
            assertOverride(selector, AUDIO_A, listOf(2), fixture)

            engine.selectSubtitleTrack(TrackSelection(0, 1))
            assertOverride(selector, TEXT_A, listOf(1), fixture)
            assertOverride(selector, AUDIO_A, listOf(2), fixture)

            // 越界 trackIndex 不得回退成 0，也不得改动已有 override
            engine.selectAudioTrack(TrackSelection(0, 3))
            assertOnlyOverride(selector, setOf(AUDIO_A, TEXT_A), fixture)
            assertOverride(selector, AUDIO_A, listOf(2), fixture)
        } finally {
            engine.release()
        }
    }

    private companion object {
        const val VIDEO = "video-main"
        const val AUDIO_A = "audio-a"
        const val AUDIO_B = "audio-b"
        const val AUDIO_UNSUPPORTED = "audio-unsupported"
        const val TEXT_A = "text-a"
        const val TEXT_B = "text-b"
    }
}
