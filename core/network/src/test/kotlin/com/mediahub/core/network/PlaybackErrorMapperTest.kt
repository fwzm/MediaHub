package com.mediahub.core.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackErrorMapperTest {

    @Test
    fun `maps http statuses to codes`() {
        assertEquals(PlaybackError.Code.AUTH_EXPIRED, PlaybackErrorMapper.fromHttpStatus(401, "u").code)
        assertEquals(PlaybackError.Code.HTTP_403, PlaybackErrorMapper.fromHttpStatus(403, "u").code)
        assertEquals(PlaybackError.Code.HTTP_404, PlaybackErrorMapper.fromHttpStatus(404, "u").code)
        assertEquals(PlaybackError.Code.HTTP_429, PlaybackErrorMapper.fromHttpStatus(429, "u").code)
        assertEquals(PlaybackError.Code.SERVER_ERROR, PlaybackErrorMapper.fromHttpStatus(500, "u").code)
        assertEquals(PlaybackError.Code.SERVER_ERROR, PlaybackErrorMapper.fromHttpStatus(503, "u").code)
        assertEquals(PlaybackError.Code.UNKNOWN, PlaybackErrorMapper.fromHttpStatus(418, "u").code)
    }

    @Test
    fun `maps io exceptions to codes`() {
        assertEquals(PlaybackError.Code.NETWORK_TIMEOUT, PlaybackErrorMapper.fromIoException(SocketTimeoutException()).code)
        assertEquals(PlaybackError.Code.DNS_ERROR, PlaybackErrorMapper.fromIoException(UnknownHostException("x")).code)
        assertEquals(PlaybackError.Code.TLS_ERROR, PlaybackErrorMapper.fromIoException(SSLException("tls")).code)
        assertEquals(PlaybackError.Code.UNKNOWN, PlaybackErrorMapper.fromIoException(IOException("boom")).code)
    }

    @Test
    fun `prefers status over exception`() {
        val err = PlaybackErrorMapper.fromHttpStatusOrIo(403, IOException("x"), "u")
        assertEquals(PlaybackError.Code.HTTP_403, err.code)
    }

    @Test
    fun `log string has no secrets`() {
        val err = PlaybackErrorMapper.fromHttpStatus(403, "https://example.com?token=secret123")
        assertTrue(err.toLogString().contains("HTTP_403"))
        assertTrue(!err.toLogString().contains("secret123"))
    }
}
