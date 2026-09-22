package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外挂字幕格式助手（P2 字幕中心切片一）：MIME 映射与语言尾缀猜测。 */
class SubtitleFormatsTest {

    // ---- MIME 映射（与 Media3 SubtitleConfiguration / mpv 共用） ----

    @Test
    fun `mime mapping covers srt ass ssa vtt`() {
        assertEquals("application/x-subrip", SubtitleFormats.mimeTypeOf("srt"))
        assertEquals("text/x-ssa", SubtitleFormats.mimeTypeOf("ass"))
        assertEquals("text/x-ssa", SubtitleFormats.mimeTypeOf("ssa"))
        assertEquals("text/vtt", SubtitleFormats.mimeTypeOf("vtt"))
    }

    @Test
    fun `mime mapping is case insensitive and rejects unknown extensions`() {
        assertEquals("application/x-subrip", SubtitleFormats.mimeTypeOf("SRT"))
        assertEquals("text/vtt", SubtitleFormats.mimeTypeOf("VTT"))
        assertNull(SubtitleFormats.mimeTypeOf("jpg"))
        assertNull(SubtitleFormats.mimeTypeOf("zip"))
        assertNull(SubtitleFormats.mimeTypeOf(""))
    }

    @Test
    fun `extension set matches covered formats`() {
        assertEquals(setOf("srt", "ass", "ssa", "vtt"), SubtitleFormats.EXTENSIONS)
    }

    // ---- 语言尾缀猜测（只推断，不猜测未知） ----

    @Test
    fun `language parsed from last dot segment`() {
        assertEquals("zh", SubtitleFormats.languageFromFileName("movie.zh.srt"))
        assertEquals("en", SubtitleFormats.languageFromFileName("movie.eng.ass"))
        assertEquals("ja", SubtitleFormats.languageFromFileName("show.jpn.vtt"))
        assertEquals("ko", SubtitleFormats.languageFromFileName("show.KR.ssa"))
    }

    @Test
    fun `chinese variants normalized to bcp47 style tags`() {
        assertEquals("zh", SubtitleFormats.languageFromFileName("a.chs.srt"))
        assertEquals("zh", SubtitleFormats.languageFromFileName("a.gb.srt"))
        assertEquals("zh-cn", SubtitleFormats.languageFromFileName("a.zh-cn.srt"))
        assertEquals("zh-tw", SubtitleFormats.languageFromFileName("a.cht.srt"))
        assertEquals("zh-tw", SubtitleFormats.languageFromFileName("a.big5.ass"))
    }

    @Test
    fun `unknown or missing suffix returns null`() {
        assertNull(SubtitleFormats.languageFromFileName("movie.srt"))
        assertNull(SubtitleFormats.languageFromFileName("movie.fx.srt")) // 非语言段
        assertEquals("zh", SubtitleFormats.languageFromFileName("movie name.zh.srt")) // 标题含空格是常态
        assertNull(SubtitleFormats.languageFromFileName("a.b zh.srt")) // 语言段内空格
        assertNull(SubtitleFormats.languageFromFileName("a.12345.srt")) // 过长段
        assertNull(SubtitleFormats.languageFromFileName(".srt"))
    }

    @Test
    fun `common iso codes are recognized and normalized`() {
        assertEquals("fr", SubtitleFormats.languageFromFileName("a.fr.srt"))
        assertEquals("zh", SubtitleFormats.languageFromFileName("a.zho.srt"))
        assertEquals("de", SubtitleFormats.languageFromFileName("a.ger.ass"))
        assertTrue(SubtitleFormats.EXTENSIONS.contains("vtt"))
    }
}
