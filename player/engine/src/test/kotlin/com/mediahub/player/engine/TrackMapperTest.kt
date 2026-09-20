package com.mediahub.player.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 轨道映射回归测试（T0003）。
 *
 * 钉死三层序号语义（ADR-032 勘误）：
 * - [AudioTrack.index] / [SubtitleTrack.index]：列表行序号（0..N-1）；
 * - [TrackSelection.groupIndex]：同类型内组序号；
 * - [TrackSelection.trackIndex]：组内轨序号。
 *
 * 行序号与组 / 轨地址的对应由 `TrackRowMap` 给出，不再假设"行序号 == 组号"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackMapperTest {

    /** 单轨组。 */
    private fun group(
        mime: String,
        supported: Boolean = true,
        selected: Boolean = false,
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

    /** 同组多轨。 */
    private fun multiGroup(
        mimes: List<String>,
        selectedTrackIndex: Int,
        languages: List<String?> = List(mimes.size) { null },
    ): Tracks.Group {
        val formats = mimes.mapIndexed { i, mime ->
            Format.Builder().setSampleMimeType(mime).setLanguage(languages.getOrNull(i)).build()
        }
        return Tracks.Group(
            TrackGroup(*formats.toTypedArray()),
            false,
            IntArray(formats.size) { C.FORMAT_HANDLED },
            BooleanArray(formats.size) { it == selectedTrackIndex },
        )
    }

    @Test
    fun `row indices are per-type ordinals and addresses carry group plus track`() {
        val tracks = Tracks(
            listOf(
                group("video/avc", selected = false),
                group("audio/mp4a-latm", selected = false, language = "chi"),
                group("audio/eac3", selected = true, language = "eng"),
                group("application/x-subrip", selected = true, language = "chi"),
            ),
        )
        val mapped = TrackMapper.mapTracks(tracks)

        // 行序号是 0..N-1（不是全局 1..2）
        assertEquals(listOf(0, 1), mapped.audioTracks.map { it.index })
        assertEquals(listOf(0), mapped.subtitleTracks.map { it.index })
        // 地址：groupIndex 为同类型内组序号，trackIndex 为组内轨号
        assertEquals(TrackSelection(1, 0, mapped.snapshotToken), mapped.selectedAudio)
        assertEquals(TrackSelection(0, 0, mapped.snapshotToken), mapped.selectedSubtitle)
        // 行映射与选中地址一致
        assertEquals(TrackSelection(1, 0, mapped.snapshotToken), mapped.rowMap.audioFor(1))
        assertEquals(TrackSelection(0, 0, mapped.snapshotToken), mapped.rowMap.subtitleFor(0))
        assertTrue(mapped.audioTracks[1].isSelected)
        assertFalse(mapped.audioTracks[0].isSelected)
        assertTrue(mapped.subtitleTracks.single().isSelected)
    }

    @Test
    fun `multi-track group expands every track and selects the actual track index`() {
        val tracks = Tracks(
            listOf(
                group("video/avc", selected = true),
                multiGroup(
                    mimes = listOf("audio/mp4a-latm", "audio/eac3"),
                    selectedTrackIndex = 1,
                    languages = listOf("eng", "chi"),
                ),
                multiGroup(
                    mimes = listOf("application/x-subrip", "text/vtt"),
                    selectedTrackIndex = 1,
                    languages = listOf("eng", "chi"),
                ),
            ),
        )
        val mapped = TrackMapper.mapTracks(tracks)

        // 逐轨展开：同组两轨都进入列表
        assertEquals(2, mapped.audioTracks.size)
        assertEquals(2, mapped.subtitleTracks.size)
        // 行序号 0/1 → 同一组（groupIndex=0）的第 0/1 轨
        assertEquals(TrackSelection(0, 0, mapped.snapshotToken), mapped.rowMap.audioFor(0))
        assertEquals(TrackSelection(0, 1, mapped.snapshotToken), mapped.rowMap.audioFor(1))
        assertEquals(TrackSelection(0, 0, mapped.snapshotToken), mapped.rowMap.subtitleFor(0))
        assertEquals(TrackSelection(0, 1, mapped.snapshotToken), mapped.rowMap.subtitleFor(1))
        // 选中态落在实际第 2 轨
        assertEquals(TrackSelection(0, 1, mapped.snapshotToken), mapped.selectedAudio)
        assertEquals(TrackSelection(0, 1, mapped.snapshotToken), mapped.selectedSubtitle)
        assertFalse(mapped.audioTracks[0].isSelected)
        assertTrue(mapped.audioTracks[1].isSelected)
        assertFalse(mapped.subtitleTracks[0].isSelected)
        assertTrue(mapped.subtitleTracks[1].isSelected)
    }

    @Test
    fun `unsupported flag and default selection flag are carried through per track`() {
        val tracks = Tracks(
            listOf(
                group("audio/vnd.dts.hd", supported = false, selected = false, selectionFlags = C.SELECTION_FLAG_DEFAULT),
                group("audio/mp4a-latm", supported = true, selected = true),
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
    fun `no audio tracks yields null selection and empty row map`() {
        val tracks = Tracks(
            listOf(
                group("video/avc", selected = true),
                group("application/x-subrip", selected = false),
            ),
        )
        val mapped = TrackMapper.mapTracks(tracks)
        assertTrue(mapped.audioTracks.isEmpty())
        assertEquals(null, mapped.selectedAudio)
        assertNull(mapped.rowMap.audioFor(0))
        assertNotNull(mapped.rowMap.subtitleFor(0))
    }

    @Test
    fun `snapshot token is shared by rows and selections of one snapshot`() {
        val mapped = TrackMapper.mapTracks(
            Tracks(
                listOf(
                    group("audio/mp4a-latm", selected = true),
                    group("application/x-subrip", selected = true),
                ),
            ),
        )
        val rowAudio = mapped.rowMap.audioFor(0)
        val rowSubtitle = mapped.rowMap.subtitleFor(0)
        assertNotNull(rowAudio)
        assertNotNull(rowSubtitle)
        assertEquals(mapped.snapshotToken, rowAudio!!.snapshotToken)
        assertEquals(mapped.snapshotToken, rowSubtitle!!.snapshotToken)
        assertEquals(mapped.snapshotToken, mapped.selectedAudio?.snapshotToken)
        assertEquals(mapped.snapshotToken, mapped.selectedSubtitle?.snapshotToken)
    }
}
