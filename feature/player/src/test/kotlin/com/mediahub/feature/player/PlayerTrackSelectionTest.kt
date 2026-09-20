package com.mediahub.feature.player

import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleTrack
import com.mediahub.player.engine.PlaybackUiState
import com.mediahub.player.engine.TrackRowMap
import com.mediahub.player.engine.TrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放器轨道行 → 引擎选择地址转换（T0003 验收标准 1、7）。
 *
 * 钉死生产路径：UI 传的是**行序号**，引擎要的是 `(groupIndex, trackIndex)`；
 * 同组多轨展开后两者不同，旧实现 `TrackSelection(track.index, 0)` 会选中错轨。
 */
class PlayerTrackSelectionTest {

    private fun audioRow(index: Int, title: String) = AudioTrack(index = index, title = title)

    private fun subtitleRow(index: Int, title: String) = SubtitleTrack(index = index, title = title)

    /** 双音频组各 1 轨 + 单字幕组 2 轨：行序号与组号不同。 */
    private fun stateWithRowMap(token: Long): PlaybackUiState = PlaybackUiState(
        audioTracks = listOf(audioRow(0, "A"), audioRow(1, "B")),
        subtitleTracks = listOf(subtitleRow(0, "S1"), subtitleRow(1, "S2")),
        selectedAudio = TrackSelection(1, 0, token),
        selectedSubtitle = TrackSelection(0, 1, token),
        trackRowMap = TrackRowMap(
            audio = mapOf(
                0 to TrackSelection(0, 0, token),
                1 to TrackSelection(1, 0, token),
            ),
            subtitle = mapOf(
                0 to TrackSelection(0, 0, token),
                1 to TrackSelection(0, 1, token),
            ),
            snapshotToken = token,
        ),
    )

    @Test
    fun `audio row resolves to its exact engine address`() {
        val state = stateWithRowMap(token = 11L)
        assertEquals(TrackSelection(0, 0, 11L), PlayerTrackSelection.addressOf(state, state.audioTracks[0]))
        assertEquals(TrackSelection(1, 0, 11L), PlayerTrackSelection.addressOf(state, state.audioTracks[1]))
    }

    @Test
    fun `second subtitle row resolves to track index 1 not 0`() {
        val state = stateWithRowMap(token = 11L)
        // 第 2 条字幕行序号 1，但地址是同一组的第 1 轨（旧实现会返回 (1, 0) 错轨）
        assertEquals(
            TrackSelection(0, 1, 11L),
            PlayerTrackSelection.addressOf(state, state.subtitleTracks[1]),
        )
        assertEquals(
            TrackSelection(0, 0, 11L),
            PlayerTrackSelection.addressOf(state, state.subtitleTracks[0]),
        )
    }

    @Test
    fun `null subtitle keeps disable semantics`() {
        val state = stateWithRowMap(token = 11L)
        assertNull(PlayerTrackSelection.addressOf(state, null))
    }

    @Test
    fun `missing row map yields null instead of a wrong address`() {
        val state = PlaybackUiState() // 无轨道映射
        assertNull(PlayerTrackSelection.addressOf(state, audioRow(0, "A")))
        assertNull(PlayerTrackSelection.addressOf(state, subtitleRow(0, "S")))
    }

    @Test
    fun `row index outside list yields null`() {
        val state = stateWithRowMap(token = 11L)
        assertNull(PlayerTrackSelection.addressOf(state, audioRow(9, "X")))
        assertNull(PlayerTrackSelection.addressOf(state, subtitleRow(9, "Y")))
    }
}
