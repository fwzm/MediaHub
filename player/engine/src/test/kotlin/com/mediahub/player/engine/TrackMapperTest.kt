package com.mediahub.player.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 轨道映射回归测试（Phase 1B-2.4）：
 * 钉死"三套 index 语义统一"——AudioTrack/SubtitleTrack.index 与 selected TrackSelection
 * 都是同类型内序号（per-type ordinal），不再使用 Tracks.groups 全局序号。
 * 旧实现（全局序号）在存在视频组时音轨序号错位（全局 1 == 音频 0）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackMapperTest {

    private fun group(
        type: Int,
        mime: String,
        supported: Boolean,
        selected: Boolean,
        language: String? = null,
        selectionFlags: Int = 0,
    ): Tracks.Group {
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setLanguage(language)
            .setSelectionFlags(selectionFlags)
            .build()
        return Tracks.Group(
            TrackGroup(format),
            false,
            intArrayOf(if (supported) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_TYPE),
            booleanArrayOf(selected),
        )
    }

    @Test
    fun `indices and selections are per-type ordinals not global group indices`() {
        val tracks = Tracks(
            listOf(
                group(C.TRACK_TYPE_VIDEO, "video/avc", supported = true, selected = false),
                group(C.TRACK_TYPE_AUDIO, "audio/mp4a-latm", supported = true, selected = false, language = "chi"),
                group(C.TRACK_TYPE_AUDIO, "audio/eac3", supported = true, selected = true, language = "eng"),
                group(C.TRACK_TYPE_TEXT, "application/x-subrip", supported = true, selected = true, language = "chi"),
            ),
        )
        val mapped = TrackMapper.mapTracks(tracks)

        // 音轨序号是 0..N-1（不是全局 1..2）
        assertEquals(listOf(0, 1), mapped.audioTracks.map { it.index })
        assertEquals(listOf(0), mapped.subtitleTracks.map { it.index })
        // 选中态用 per-type 序号表达，UI 可直接回传给引擎（MappedTrackInfo.getTrackGroups(type) 同序）
        assertEquals(TrackSelection(1, 0), mapped.selectedAudio)
        assertEquals(TrackSelection(0, 0), mapped.selectedSubtitle)
        assertTrue(mapped.audioTracks[1].isSelected)
        assertFalse(mapped.audioTracks[0].isSelected)
        assertTrue(mapped.subtitleTracks.single().isSelected)
    }

    @Test
    fun `unsupported flag and default selection flag are carried through`() {
        val tracks = Tracks(
            listOf(
                group(
                    C.TRACK_TYPE_AUDIO, "audio/vnd.dts.hd",
                    supported = false, selected = false,
                    selectionFlags = C.SELECTION_FLAG_DEFAULT,
                ),
                group(C.TRACK_TYPE_AUDIO, "audio/mp4a-latm", supported = true, selected = true),
            ),
        )
        val mapped = TrackMapper.mapTracks(tracks)

        assertFalse(mapped.audioTracks[0].isSupported)
        assertTrue(mapped.audioTracks[0].isDefault)
        assertTrue(mapped.audioTracks[1].isSupported)
        assertFalse(mapped.audioTracks[1].isDefault)
        // 解码器查找在 Robolectric 下无真 codec，容忍 null（诊断信息非硬依赖）
        assertEquals(null, mapped.audioTracks[0].decoderName)
    }

    @Test
    fun `no audio tracks yields null selection`() {
        val tracks = Tracks(
            listOf(
                group(C.TRACK_TYPE_VIDEO, "video/avc", supported = true, selected = true),
                group(C.TRACK_TYPE_TEXT, "application/x-subrip", supported = true, selected = false),
            ),
        )
        val mapped = TrackMapper.mapTracks(tracks)
        assertTrue(mapped.audioTracks.isEmpty())
        assertEquals(null, mapped.selectedAudio)
    }
}
