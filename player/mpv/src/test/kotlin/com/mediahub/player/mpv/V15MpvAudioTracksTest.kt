package com.mediahub.player.mpv

import com.mediahub.player.engine.TrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V15-A（先红后绿）：mpv 音轨枚举与选轨。
 *
 * 真实缺陷（真机复现）：mpv 引擎播放双音轨 MKV 时，「音轨」面板显示「未发现音轨」。
 * 根因：MpvPlaybackEngine.selectAudioTrack 为空实现（= Unit），且从不发布含 audioTracks 的状态。
 *
 * 本测试先只固定**纯映射契约**（不依赖 JNI/真机）：
 * 给定 mpv 的 track-list 属性快照，应能映射出与 UI 一致的 AudioTrack 列表与选中态。
 * RED 依据：当前不存在该映射 API。
 */
class V15MpvAudioTracksTest {

    private fun snapshot(vararg tracks: Triple<String, String?, Boolean>): List<Map<String, String?>> =
        tracks.mapIndexed { i, (id, lang, selected) ->
            mapOf(
                "id" to id,
                "type" to "audio",
                "lang" to lang,
                "selected" to (if (selected) "yes" else "no"),
                "index" to i.toString(),
            )
        }

    @Test
    fun `maps mpv audio track list into ui audio tracks with stable order and selection`() {
        val mapped = MpvTrackMapper.mapAudioTracks(snapshot(Triple("1", "eng", true), Triple("2", "chi", false)))

        assertEquals("双音轨必须映射为 2 条", 2, mapped.size)
        assertEquals("eng", mapped[0].language)
        assertEquals("chi", mapped[1].language)
        assertTrue("首轨为选中", mapped[0].isSelected)
        assertTrue("次轨未选中", !mapped[1].isSelected)
        assertEquals("index 必须是同类型内序号 0..N-1", 0, mapped[0].index)
        assertEquals(1, mapped[1].index)
    }

    @Test
    fun `ignores non audio tracks and keeps audio-only indices`() {
        val rows: List<Map<String, String?>> = listOf(
            mapOf("id" to "1", "type" to "video", "selected" to "yes", "index" to "0"),
            mapOf("id" to "2", "type" to "audio", "lang" to "eng", "selected" to "yes", "index" to "1"),
            mapOf("id" to "3", "type" to "sub", "selected" to "no", "index" to "2"),
            mapOf("id" to "4", "type" to "audio", "lang" to "jpn", "selected" to "no", "index" to "3"),
        )
        val mapped = MpvTrackMapper.mapAudioTracks(rows)

        assertEquals("只有 audio 类型参与", 2, mapped.size)
        assertEquals(0, mapped[0].index)
        assertEquals(1, mapped[1].index)
        assertEquals("eng", mapped[0].language)
        assertEquals("jpn", mapped[1].language)
    }

    @Test
    fun `empty or missing fields degrade conservatively without fabricating`() {
        val rows: List<Map<String, String?>> = listOf(mapOf("id" to "1", "type" to "audio", "selected" to "yes"))
        val mapped = MpvTrackMapper.mapAudioTracks(rows)

        assertEquals(1, mapped.size)
        assertEquals("缺失语言必须为 null，不得伪造", null, mapped[0].language)
        assertEquals("缺失编码必须为 null，不得伪造", null, mapped[0].codec)
        assertEquals("缺失声道必须为 null，不得伪造", null, mapped[0].channels)
        assertTrue(mapped[0].isSelected)
    }

    @Test
    fun `maps 1-based audio id for aid selection while exposing 0-based ui index`() {
        val rows: List<Map<String, String?>> = listOf(
            mapOf("id" to "1", "type" to "audio", "lang" to "eng", "selected" to "yes", "index" to "0"),
            mapOf("id" to "2", "type" to "audio", "lang" to "chi", "selected" to "no", "index" to "1"),
        )
        val ids = MpvTrackMapper.nativeAudioIds(rows)

        // UI 用 0..N-1 的 index 选轨；引擎侧必须能取回 mpv 的 track id（≠ stream index）
        assertEquals("1", ids[0])
        assertEquals("2", ids[1])
        assertEquals("index 0 对应 aid 1", "1", MpvTrackMapper.nativeAudioId(rows, TrackSelection(0, 0)))
        assertEquals("index 1 对应 aid 2", "2", MpvTrackMapper.nativeAudioId(rows, TrackSelection(1, 0)))
        assertEquals("null 表示 aid=no", null, MpvTrackMapper.nativeAudioId(rows, null))
    }
}
