package com.mediahub.core.common

import com.mediahub.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressAutoDetectTest {

    @Test
    fun `bare emby host probes media ports in preferred scheme order`() {
        val urls = ServerAddressAutoDetect.candidates("192.168.1.5", true, ServerType.EMBY).map { it.url }
        assertEquals(
            listOf(
                "https://192.168.1.5",
                "https://192.168.1.5:8920",
                "http://192.168.1.5:8096",
            ),
            urls,
        )
    }

    @Test
    fun `bare jellyfin host with http preference probes 8096 before 8920`() {
        val urls = ServerAddressAutoDetect.candidates("media.example", false, ServerType.JELLYFIN).map { it.url }
        assertEquals(
            listOf(
                "http://media.example",
                "http://media.example:8096",
                "https://media.example:8920",
            ),
            urls,
        )
    }

    @Test
    fun `explicit port is never expanded`() {
        val urls = ServerAddressAutoDetect.candidates("http://192.168.1.5:5000", true, ServerType.EMBY).map { it.url }
        assertEquals(listOf("http://192.168.1.5:5000"), urls)
    }

    @Test
    fun `reverse proxy path is never expanded`() {
        val urls = ServerAddressAutoDetect.candidates("media.example/emby", true, ServerType.EMBY).map { it.url }
        assertEquals(listOf("https://media.example/emby"), urls)
    }

    @Test
    fun `non media server types keep only the normalized address`() {
        val urls = ServerAddressAutoDetect.candidates("nas.example", true, ServerType.WEBDAV).map { it.url }
        assertEquals(listOf("https://nas.example"), urls)
    }

    @Test
    fun `invalid input yields no candidates`() {
        assertTrue(ServerAddressAutoDetect.candidates("", true, ServerType.EMBY).isEmpty())
        assertTrue(ServerAddressAutoDetect.candidates("ftp://nas.example", true, ServerType.EMBY).isEmpty())
    }

    @Test
    fun `ipv6 host keeps brackets on media port candidates`() {
        val urls = ServerAddressAutoDetect.candidates("[2001:db8::1]", true, ServerType.EMBY).map { it.url }
        assertEquals("https://[2001:db8::1]:8920", urls[1])
        assertEquals("http://[2001:db8::1]:8096", urls[2])
    }
}
