package com.mediahub.player.engine

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.mediahub.core.network.PlaybackError
import com.mediahub.core.network.PlaybackErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Media3 播放错误里的 HTTP 状态码解包（审计条目 22）：
 * `ERROR_CODE_IO_BAD_HTTP_STATUS` 的 cause 携带 `InvalidResponseCodeException`，
 * 必须映射为 403/404/429/5xx 的结构化错误，而不是一律 UNKNOWN。
 *
 * 取不到状态码时保持 UNKNOWN（不猜）。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackHttpStatusExtractorTest {

    private val streamUrl = "https://media.invalid/emby/Videos/1/stream.mp4"

    private fun badHttp(cause: Throwable?): PlaybackException = PlaybackException(
        "bad http status",
        cause,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    private fun invalidResponse(code: Int): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            code,
            "response $code",
            null,
            emptyMap(),
            DataSpec(Uri.parse(streamUrl)),
            ByteArray(0),
        )

    @Test
    fun `403 cause maps to HTTP_403 with the request uri`() {
        val extracted = PlaybackHttpStatusExtractor.extract(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            invalidResponse(403),
        )
        assertNotNull("403 必须被解包出来", extracted)
        assertEquals(403, extracted!!.statusCode)
        assertEquals(streamUrl, extracted.uri)
        assertEquals(
            PlaybackError.Code.HTTP_403,
            PlaybackErrorMapper.fromHttpStatus(extracted.statusCode, extracted.uri).code,
        )
    }

    @Test
    fun `404 429 and 5xx map through the shared mapper`() {
        assertEquals(
            PlaybackError.Code.HTTP_404,
            PlaybackErrorMapper.fromHttpStatus(404, streamUrl).code,
        )
        assertEquals(
            PlaybackError.Code.HTTP_429,
            PlaybackErrorMapper.fromHttpStatus(429, streamUrl).code,
        )
        assertEquals(
            PlaybackError.Code.SERVER_ERROR,
            PlaybackErrorMapper.fromHttpStatus(503, streamUrl).code,
        )
    }

    @Test
    fun `bad http status without cause stays unknown`() {
        assertNull(
            "取不到状态码时必须返回 null（保持 UNKNOWN，不猜）",
            PlaybackHttpStatusExtractor.extract(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null),
        )
        assertNull(
            PlaybackHttpStatusExtractor.extract(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                IOException("socket closed"),
            ),
        )
        assertEquals(
            PlaybackError.Code.UNKNOWN,
            mapPlaybackException(badHttp(null)).code,
        )
    }

    @Test
    fun `non http error codes are not unpacked`() {
        assertNull(
            PlaybackHttpStatusExtractor.extract(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                invalidResponse(403),
            ),
        )
    }

    @Test
    fun `mapPlaybackException produces http codes for real media3 exceptions`() {
        val mapped = mapPlaybackException(badHttp(invalidResponse(403)))
        assertEquals(PlaybackError.Code.HTTP_403, mapped.code)
        assertEquals("403", mapped.details["status"])

        val notFound = mapPlaybackException(badHttp(invalidResponse(404)))
        assertEquals(PlaybackError.Code.HTTP_404, notFound.code)
        assertTrue("404 文案必须说明是媒体文件不存在", notFound.message!!.contains("404"))
    }

    @Test
    fun `http 4xx is not an mpv fallback trigger`() {
        assertTrue(
            "解码类错误仍应降级 mpv",
            PlaybackError.Code.DECODER_ERROR in SwitchablePlaybackEngine.FALLBACK_ERROR_CODES,
        )
        assertTrue(
            PlaybackError.Code.UNSUPPORTED_CODEC in SwitchablePlaybackEngine.FALLBACK_ERROR_CODES,
        )
        assertTrue(
            "HTTP 4xx/5xx 用同一网络栈与凭据，mpv 救不回，不降级",
            PlaybackError.Code.HTTP_403 !in SwitchablePlaybackEngine.FALLBACK_ERROR_CODES,
        )
        assertTrue(PlaybackError.Code.HTTP_404 !in SwitchablePlaybackEngine.FALLBACK_ERROR_CODES)
        assertTrue(PlaybackError.Code.SERVER_ERROR !in SwitchablePlaybackEngine.FALLBACK_ERROR_CODES)
    }
}
