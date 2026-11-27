package com.mediahub.core.common

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务器协议/端口自动识别（审计条目 75）。
 *
 * 契约：
 * 1. 显式 scheme + 显式端口 → 唯一候选（尊重用户输入，不擅自改端口）；
 * 2. 显式 scheme 无端口 → 该 scheme 的常见端口；
 * 3. 无 scheme 无端口 → 按 preferHttps 排序的双协议常见端口，数量有上限、去重；
 * 4. scheme 与端口必须匹配（绝不生成 http:8920 / https:8096）；
 * 5. detect 必须按顺序真实探测，任一可达即返回，不可静默返回第一个。
 */
class ServerAddressDetectorTest {

    private fun plan(input: String, preferHttps: Boolean = true): List<ServerAddressDetector.Candidate> =
        ServerAddressDetector.plan(input, preferHttps)

    @Test
    fun `explicit scheme and port yields a single candidate`() {
        val candidates = plan("http://192.168.1.5:8096")
        assertEquals(1, candidates.size)
        assertEquals("http://192.168.1.5:8096", candidates.single().url)
        assertEquals("http", candidates.single().scheme)
        assertEquals(8096, candidates.single().port)
    }

    @Test
    fun `explicit https without port only probes https common ports`() {
        val candidates = plan("https://media.example")
        assertEquals(
            listOf("https://media.example:8920", "https://media.example:443"),
            candidates.map { it.url },
        )
        assertTrue("显式 https 时不得擅自探测 http", candidates.all { it.scheme == "https" })
    }

    @Test
    fun `explicit http without port probes http common ports first`() {
        val candidates = plan("http://media.example")
        assertEquals("http://media.example:8096", candidates.first().url)
        assertTrue(candidates.all { it.scheme == "http" })
    }

    @Test
    fun `bare host prefers https and never mixes scheme with foreign port`() {
        val candidates = plan("media.example", preferHttps = true)
        assertEquals("https://media.example:8920", candidates.first().url)
        assertTrue(
            "无显式协议时应同时覆盖 http 8096（Emby 默认端口）",
            candidates.map { it.url }.contains("http://media.example:8096"),
        )
        assertTrue(
            "候选数量必须有上限（实际 ${candidates.size}）",
            candidates.size <= ServerAddressDetector.MAX_CANDIDATES,
        )
        assertFalse("禁止生成 http:8920", candidates.any { it.scheme == "http" && it.port == 8920 })
        assertFalse("禁止生成 http:443", candidates.any { it.scheme == "http" && it.port == 443 })
        assertFalse("禁止生成 https:8096", candidates.any { it.scheme == "https" && it.port == 8096 })
        assertFalse("禁止生成 https:80", candidates.any { it.scheme == "https" && it.port == 80 })
        assertEquals("候选必须去重", candidates.size, candidates.map { it.url }.toSet().size)
    }

    @Test
    fun `bare host with preferHttps off starts from http 8096`() {
        val candidates = plan("192.168.1.5", preferHttps = false)
        assertEquals("http://192.168.1.5:8096", candidates.first().url)
        assertTrue(candidates.map { it.url }.contains("https://192.168.1.5:8920"))
    }

    @Test
    fun `reverse proxy subpath is preserved in every candidate`() {
        val candidates = plan("media.example/jellyfin", preferHttps = true)
        assertEquals("https://media.example:8920/jellyfin", candidates.first().url)
        assertTrue(candidates.all { it.url.endsWith("/jellyfin") })
    }

    @Test
    fun `invalid input yields no candidates instead of probing`() {
        assertTrue(plan("ftp://media.example").isEmpty())
        assertTrue(plan("   ").isEmpty())
        assertTrue(plan("media.example?x=1").isEmpty())
        assertTrue(plan("media.example:70000").isEmpty())
    }

    @Test
    fun `detect returns the first reachable candidate and records attempts`() = runBlocking {
        val candidates = ServerAddressDetector.plan("media.example", preferHttps = true)
        val probed = mutableListOf<String>()
        val result = ServerAddressDetector.detect(candidates) { url ->
            probed += url
            url == candidates[1].url
        }
        val detected = result as ServerAddressDetector.DetectionResult.Detected
        assertEquals(candidates[1].url, detected.address)
        assertEquals(candidates[1].scheme, detected.scheme)
        assertEquals(candidates[1].port, detected.port)
        assertEquals(candidates.take(2).map { it.url }, detected.attempted)
        assertEquals(detected.attempted, probed)
    }

    @Test
    fun `detect reports every attempt when nothing is reachable`() = runBlocking {
        val candidates = ServerAddressDetector.plan("10.0.0.2", preferHttps = false)
        val probed = mutableListOf<String>()
        val result = ServerAddressDetector.detect(candidates) { url ->
            probed += url
            false
        }
        val notDetected = result as ServerAddressDetector.DetectionResult.NotDetected
        assertEquals(candidates.map { it.url }, notDetected.attempted)
        assertEquals(candidates.map { it.url }, probed)
        assertTrue("全部不可达时必须如实尝试全部候选", probed.size == candidates.size)
    }

    @Test
    fun `detect with empty plan reports nothing attempted`() = runBlocking {
        val result = ServerAddressDetector.detect(emptyList()) { true }
        assertEquals(
            emptyList<String>(),
            (result as ServerAddressDetector.DetectionResult.NotDetected).attempted,
        )
    }
}
