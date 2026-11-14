package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 条目 136：无扩展名 HLS 地址的内容识别。
 *
 * 判定只依赖响应事实（Content-Type / 播放列表首行标记），
 * 与 URL 形式无关，因此可覆盖 `/play?id=1` 这类完全没有扩展名的地址。
 */
class HlsContentDetectorTest {

    @Test
    fun `hls content types are recognized with and without charset parameters`() {
        assertEquals(
            HlsContentDetector.MIME_TYPE,
            HlsContentDetector.detect("application/vnd.apple.mpegurl", null),
        )
        assertEquals(
            HlsContentDetector.MIME_TYPE,
            HlsContentDetector.detect("application/x-mpegURL; charset=utf-8", null),
        )
        assertEquals(
            HlsContentDetector.MIME_TYPE,
            HlsContentDetector.detect("audio/mpegurl", null),
        )
    }

    @Test
    fun `playlist body is recognized when the server sends a generic content type`() {
        val body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n"
        assertEquals(
            HlsContentDetector.MIME_TYPE,
            HlsContentDetector.detect("application/octet-stream", body),
        )
    }

    @Test
    fun `leading bom and whitespace do not hide the playlist marker`() {
        assertEquals(
            HlsContentDetector.MIME_TYPE,
            HlsContentDetector.detect(null, "\uFEFF\r\n  #EXTM3U\n#EXT-X-VERSION:3"),
        )
    }

    @Test
    fun `non playlist bodies and content types are not misidentified`() {
        assertNull(HlsContentDetector.detect("video/mp4", "\u0000\u0000\u0000\u0018ftypisom"))
        assertNull(HlsContentDetector.detect("application/octet-stream", "not a playlist"))
        assertNull(HlsContentDetector.detect(null, null))
    }
}
