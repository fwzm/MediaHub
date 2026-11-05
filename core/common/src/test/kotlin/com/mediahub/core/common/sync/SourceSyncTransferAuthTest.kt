package com.mediahub.core.common.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 传输层可选对端认证（W6-25，对齐 Windows W6-24）的互操作对照。
 *
 * 资源 windows-auth-vectors.json 由 D:/mh-verify/auth-vectors（临时 console 工程，不入库）直接调
 * D:/mh-w6/windows/src/MediaHub.Sync 的 SourceSyncTransfer / SourceSyncTransferAuthenticator 生成：
 * - success：5 条 tag（含错口令那一条）；
 * - streams：3 条完整帧流（单块、两块、重放序列）；
 * - receiveCases：12 条坏流，每条带 Windows 亲口给出的稳定码与诊断文本；
 * - sendFailures：2 条「一个字都不该写」的发送失败；
 * - optionsToString：4 条打码格式化结果。
 *
 * 对照口径：码名逐字相等、帧流字节逐字节相等、诊断文本逐字相等（认证相关分支刻意用 Windows 原文，
 * 方便两端日志直接 diff）。
 */
class SourceSyncTransferAuthTest {

    private val vectors by lazy {
        Json.parseToJsonElement(resource("windows-auth-vectors.json")).jsonObject
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/sync/" + name)) { "缺少测试资源 /sync/" + name }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun resourceBytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/sync/" + name)) { "缺少测试资源 /sync/" + name }
            .use { it.readBytes() }

    private fun secret(name: String): ByteArray =
        vectors.getValue("secrets").jsonObject.getValue(name).jsonPrimitive.content.toByteArray(Charsets.UTF_8)

    private fun envelope(name: String): ByteArray =
        hex(vectors.getValue("envelopes").jsonObject.getValue(name).jsonObject.getValue("hex").jsonPrimitive.content)

    private fun nonce(name: String): String =
        vectors.getValue("nonces").jsonObject.getValue(name).jsonPrimitive.content

    private fun nullableString(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonNull -> null
        else -> element.jsonPrimitive.content
    }

    private fun hex(text: String): ByteArray {
        val clean = text.trim()
        require(clean.length % 2 == 0) { "hex 长度必须是偶数，实际 " + clean.length }
        val out = ByteArray(clean.length / 2)
        for (index in out.indices) {
            val high = Character.digit(clean[index * 2], 16)
            val low = Character.digit(clean[index * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "不是合法 hex：" + clean.substring(index * 2, index * 2 + 2) }
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun toHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(digits[value ushr 4])
            builder.append(digits[value and 0x0F])
        }
        return builder.toString()
    }

    private fun okOrFail(result: SourceSyncTransferResult, what: String): SourceSyncTransferResult.Ok {
        if (result is SourceSyncTransferResult.Failed) {
            throw AssertionError(what + " 本应成功，实际失败：" + result.reason + " / " + result.message)
        }
        return result as SourceSyncTransferResult.Ok
    }

    private fun failureOf(result: SourceSyncTransferResult): SourceSyncTransferResult.Failed {
        if (result is SourceSyncTransferResult.Ok) {
            throw AssertionError("本应失败却成功了（" + result.envelope.size + " 字节 / " + result.frameCount + " 帧）")
        }
        return result as SourceSyncTransferResult.Failed
    }

    // ------------------------------------------------------------------ 常量与码表

    @Test
    fun authentication_constants_match_the_windows_contract() {
        val constants = vectors.getValue("constants").jsonObject
        assertEquals(SourceSyncTransferAuthenticator.TAG_FRAME_KIND, constants.getValue("tagFrameKind").jsonPrimitive.int)
        assertEquals(SourceSyncTransferAuthenticator.TAG_BYTES, constants.getValue("tagBytes").jsonPrimitive.int)
        assertEquals(SourceSyncTransferAuthenticator.TAG_FRAME_BYTES, constants.getValue("tagFrameBytes").jsonPrimitive.int)
        assertEquals(SourceSyncTransferAuthenticator.NONCE_ENTROPY_BYTES, constants.getValue("nonceEntropyBytes").jsonPrimitive.int)
        assertEquals(SourceSyncNonceCache.DEFAULT_CAPACITY, constants.getValue("nonceCacheDefaultCapacity").jsonPrimitive.int)
        assertEquals(SourceSyncTransfer.MAXIMUM_ENVELOPE_BYTES, constants.getValue("maximumEnvelopeBytes").jsonPrimitive.int)
        assertEquals(SourceSyncTransfer.CHUNK_PAYLOAD_BYTES, constants.getValue("chunkPayloadBytes").jsonPrimitive.int)
        assertEquals(SourceSyncTransfer.MAXIMUM_CHALLENGE_BYTES, constants.getValue("maximumChallengeBytes").jsonPrimitive.int)
        // 帧类型值本身是契约：0x03 与 0x01 / 0x02 同处一个帧空间。
        assertEquals(3, SourceSyncTransferAuthenticator.TAG_FRAME_KIND)
        assertEquals(33, SourceSyncTransferAuthenticator.TAG_FRAME_BYTES)
        assertEquals(64, SourceSyncNonceCache.DEFAULT_CAPACITY)
    }

    @Test
    fun auth_reason_vocabulary_is_identical_and_disjoint_from_the_transfer_codes() {
        val expected = vectors.getValue("authReasons").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected, SourceSyncTransferAuthReasons.ALL)
        assertEquals(5, SourceSyncTransferAuthReasons.ALL.size)
        assertEquals(SourceSyncTransferAuthReasons.ALL.size, SourceSyncTransferAuthReasons.ALL.toSet().size)

        // 十码集一个字都没被改动（Windows 侧的同一份常量由向量带过来）。
        val transferReasons = vectors.getValue("transferReasons").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(transferReasons, SourceSyncTransferReasons.ALL)
        assertEquals(10, SourceSyncTransferReasons.ALL.size)

        // 两套码必须不相交：交叉了会让「认证失败」和「帧坏」在日志里分不开。
        val overlap = SourceSyncTransferAuthReasons.ALL.intersect(SourceSyncTransferReasons.ALL.toSet())
        assertTrue("两套码有交集：" + overlap, overlap.isEmpty())
    }

    // ------------------------------------------------------------------ Windows 的 tag

    @Test
    fun every_success_vector_reproduces_the_windows_tag() {
        val cases = vectors.getValue("success").jsonArray
        assertEquals(5, cases.size)
        for (element in cases) {
            val record = element.jsonObject
            val name = record.getValue("name").jsonPrimitive.content
            val key = secret(record.getValue("secret").jsonPrimitive.content)
            val text = record.getValue("nonce").jsonPrimitive.content
            val data = envelope(record.getValue("envelope").jsonPrimitive.content)
            val expected = record.getValue("tagHex").jsonPrimitive.content
            assertEquals(name + " 的 tag 必须与 Windows 逐字节相同", expected, toHex(SourceSyncTransferAuthenticator.createTag(key, text, data)))
            assertEquals(name + " 的 tag 必须是 32 字节", 32, hex(expected).size)
        }
    }

    @Test
    fun verify_accepts_the_windows_tags_and_rejects_a_flipped_byte() {
        for (element in vectors.getValue("success").jsonArray) {
            val record = element.jsonObject
            val name = record.getValue("name").jsonPrimitive.content
            val key = secret(record.getValue("secret").jsonPrimitive.content)
            val text = record.getValue("nonce").jsonPrimitive.content
            val data = envelope(record.getValue("envelope").jsonPrimitive.content)
            val tag = hex(record.getValue("tagHex").jsonPrimitive.content)
            assertTrue(name + " 的 tag 必须验过", SourceSyncTransferAuthenticator.verifyTag(key, text, data, tag))

            val flipped = tag.copyOf()
            flipped[0] = (flipped[0].toInt() xor 0x01).toByte()
            assertFalse(name + " 改一个 bit 必须验不过", SourceSyncTransferAuthenticator.verifyTag(key, text, data, flipped))
            // 长度不对直接 false（不等长数组不能做常量时间比较）。
            assertFalse("截短的 tag 必须验不过", SourceSyncTransferAuthenticator.verifyTag(key, text, data, tag.copyOf(31)))
            assertFalse("空口令什么都不该验过", SourceSyncTransferAuthenticator.verifyTag(ByteArray(0), text, data, tag))
        }
    }

    // ------------------------------------------------------------------ 帧流逐字节

    @Test
    fun the_authenticated_stream_matches_the_windows_frame_stream_byte_for_byte() {
        for (element in vectors.getValue("streams").jsonArray) {
            val record = element.jsonObject
            val name = record.getValue("name").jsonPrimitive.content
            val challenge = record.getValue("challenge").jsonPrimitive.content
            val key = secret(record.getValue("secret").jsonPrimitive.content)
            val data = envelope(record.getValue("envelope").jsonPrimitive.content)
            val expected = record.getValue("streamHex").jsonPrimitive.content

            val output = ByteArrayOutputStream()
            val sent = okOrFail(
                SourceSyncTransfer.sendTo(output, data, challenge, SourceSyncTransferOptions.withSecret(key)),
                name + " 的发送",
            )
            assertEquals(name + " 的帧流必须与 Windows 逐字节相同", expected, toHex(output.toByteArray()))
            assertEquals(challenge, sent.challenge)
            assertEquals(challenge, sent.challenge?.trim())
            assertEquals(
                name + " 的帧数（挑战 + 块 + tag）",
                SourceSyncTransfer.chunkCount(data.size) + 2,
                sent.frameCount,
            )
        }
    }

    @Test
    fun an_authenticated_stream_decodes_back_to_the_same_envelope() {
        for (element in vectors.getValue("streams").jsonArray) {
            val record = element.jsonObject
            val name = record.getValue("name").jsonPrimitive.content
            val challenge = record.getValue("challenge").jsonPrimitive.content
            val key = secret(record.getValue("secret").jsonPrimitive.content)
            val data = envelope(record.getValue("envelope").jsonPrimitive.content)
            val stream = hex(record.getValue("streamHex").jsonPrimitive.content)
            val frameCount = record["frameCount"]

            val options = SourceSyncTransferOptions.withSecret(key, SourceSyncNonceCache(), record["deviceId"]?.jsonPrimitive?.content)
            val decoded = okOrFail(SourceSyncTransfer.decode(stream, challenge, options), name + " 的解码")
            assertArrayEquals(name + " 必须重组回同一个信封", data, decoded.envelope)
            assertEquals(challenge, decoded.challenge)
            if (frameCount != null) {
                assertEquals(name + " 的帧数", frameCount.jsonPrimitive.int, decoded.frameCount)
            }
        }
    }

    @Test
    fun the_same_nonce_twice_is_a_replay() {
        val record = vectors.getValue("streams").jsonArray
            .map { it.jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == "the_same_nonce_twice_is_a_replay" }
        val challenge = record.getValue("challenge").jsonPrimitive.content
        val key = secret(record.getValue("secret").jsonPrimitive.content)
        val stream = hex(record.getValue("streamHex").jsonPrimitive.content)
        val cache = SourceSyncNonceCache()
        val options = SourceSyncTransferOptions.withSecret(key, cache, "device-b")

        val first = okOrFail(SourceSyncTransfer.decode(stream, challenge, options), "第一次接收")
        assertEquals(challenge, first.challenge)
        assertEquals(1, cache.count)
        assertEquals("auth-replayed", failureOf(SourceSyncTransfer.decode(stream, challenge, options)).reason)
        assertEquals("重放被拒时窗口不变", 1, cache.count)
    }

    // ------------------------------------------------------------------ Windows 的坏流

    @Test
    fun every_receive_case_reports_the_reason_windows_reports() {
        val cases = vectors.getValue("receiveCases").jsonArray
        assertEquals(12, cases.size)
        val mismatches = ArrayList<String>()
        for (element in cases) {
            val record = element.jsonObject
            val name = record.getValue("name").jsonPrimitive.content
            val stream = hex(record.getValue("streamHex").jsonPrimitive.content)
            val expectedChallenge = nullableString(record["expectedChallenge"])
            val repeats = record.getValue("repeats").jsonPrimitive.int
            val expectedReasons = record.getValue("expectedReasons").jsonArray.map { nullableString(it) }
            val details = record.getValue("details").jsonArray.map { nullableString(it) }
            val options = optionsOf(record, SourceSyncNonceCache())

            for (run in 0 until repeats) {
                val result = SourceSyncTransfer.decode(stream, expectedChallenge, options)
                val actualReason = if (result is SourceSyncTransferResult.Ok) null else (result as SourceSyncTransferResult.Failed).reason
                val expectedReason = expectedReasons[run]
                if (actualReason != expectedReason) {
                    mismatches.add(name + " 第 " + (run + 1) + " 次：本端 " + actualReason + "，Windows " + expectedReason)
                    continue
                }
                val expectedDetail = details.getOrNull(run)
                if (expectedDetail != null && result is SourceSyncTransferResult.Failed) {
                    // 认证与 tag 分支刻意用 Windows 原文，这里逐字比对，防两端日志对不上；
                    // 其它分支（挑战不符、帧截断）沿用 W6-22 的中文诊断，不参与文本比对。
                    if (detailShouldMatch(expectedReason, expectedDetail) && result.message != expectedDetail) {
                        mismatches.add(name + "：诊断文本不一致，本端「" + result.message + "」，Windows「" + expectedDetail + "」")
                    }
                }
            }
        }
        assertTrue("与 Windows 的行为不一致：" + mismatches.joinToString(" | "), mismatches.isEmpty())
    }

    private fun detailShouldMatch(expectedReason: String?, detail: String): Boolean =
        expectedReason != null &&
            (SourceSyncTransferAuthReasons.ALL.contains(expectedReason) ||
                detail.contains("tag frame") ||
                detail.startsWith("Unknown frame kind"))

    @Test
    fun every_send_failure_reports_the_windows_reason_and_writes_nothing() {
        val cases = vectors.getValue("sendFailures").jsonArray
        assertEquals(2, cases.size)
        val data = envelope("envelope64")
        for (element in cases) {
            val record = element.jsonObject
            val name = record.getValue("name").jsonPrimitive.content
            val challenge = nullableString(record["challenge"])
            val secretSpec = nullableString(record["secret"])
            val expectedReason = record.getValue("reason").jsonPrimitive.content
            val expectedDetail = record.getValue("detail").jsonPrimitive.content
            val expectedBytes = record.getValue("bytesWritten").jsonPrimitive.int

            val options = if (secretSpec == null) {
                SourceSyncTransferOptions.withSecret(null, null, "device-b")
            } else {
                SourceSyncTransferOptions.withSecret(secret(secretSpec), null, "device-b")
            }
            val output = ByteArrayOutputStream()
            val failure = failureOf(SourceSyncTransfer.sendTo(output, data, challenge, options))
            assertEquals(name + " 的稳定码", expectedReason, failure.reason)
            assertEquals(name + " 的字节数", expectedBytes, output.size())
            assertEquals(name + " 的字节数必须为 0", 0, output.size())
            assertEquals(name + " 的诊断文本", expectedDetail, failure.message)
        }
    }

    // ------------------------------------------------------------------ options 打码

    @Test
    fun options_to_string_redacts_the_secret_and_matches_windows() {
        val expected = vectors.getValue("optionsToString").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(4, expected.size)
        val good = secret("good")
        val actual = listOf(
            SourceSyncTransferOptions.OFF.toString(),
            SourceSyncTransferOptions.withSecret(good, null, "device-b").toString(),
            SourceSyncTransferOptions.withSecret(null, null, null).toString(),
            SourceSyncTransferOptions.withSecret(ByteArray(32), null, "device-c").toString(),
        )
        assertEquals(expected, actual)
        // 口令本体绝不出现在文本里（异常、日志、toString 都走这一个格式化）。
        val text = String(good, Charsets.UTF_8)
        for (line in actual) {
            assertFalse("options 文本泄漏了口令：" + line, line.contains(text))
        }
        assertTrue(actual[1].contains("<redacted " + good.size + " bytes>"))
        assertFalse(SourceSyncTransferOptions.withSecret(good).hasSecret.not())
        assertFalse(SourceSyncTransferOptions.OFF.hasSecret)
        assertTrue(SourceSyncTransferOptions.withSecret(null).authenticate)
        assertFalse("没有口令时 hasSecret 必须是 false", SourceSyncTransferOptions.withSecret(null).hasSecret)
    }

    // ------------------------------------------------------------------ 关闭时与 W6-22 全等

    @Test
    fun authentication_off_produces_exactly_the_bytes_it_produced_before() {
        val data = envelope("goldenDeviceA")
        val challenge = nonce("first")
        val baseline = SourceSyncTransfer.encode(data, challenge)
        assertEquals(baseline.size, toHex(baseline).length / 2)
        assertEquals(
            "OFF 必须与「不传 options」逐字节相同",
            toHex(SourceSyncTransfer.encode(data, challenge)),
            toHex(sendBytes(data, challenge, SourceSyncTransferOptions.OFF)),
        )
        assertEquals(
            "OFF 必须与旧调用（options 为 null）逐字节相同",
            toHex(sendBytes(data, challenge, null)),
            toHex(sendBytes(data, challenge, SourceSyncTransferOptions.OFF)),
        )
        // 帧流里没有 0x03：最后一个帧必须是数据块。
        val frames = splitFrames(baseline)
        assertEquals(SourceSyncTransfer.chunkCount(data.size) + 1, frames.size)
        for (frame in frames) {
            assertNotEquals(SourceSyncTransferAuthenticator.TAG_FRAME_KIND, frame[0].toInt() and 0xFF)
        }
    }

    @Test
    fun authentication_off_parses_the_same_frames_the_w6_22_vectors_recorded() {
        val record = Json.parseToJsonElement(resource("windows-transfer-vectors.json")).jsonObject
        val expected = record.getValue("vectors").jsonObject.getValue("withChallenge").jsonObject.getValue("framesHex").jsonPrimitive.content
        val challenge = record.getValue("frozenChallenge").jsonPrimitive.content
        val data = resourceBytes("windows-envelope-device-a.json")
        assertEquals("关闭认证时送往 Windows 的帧流必须一个字节都不变", expected, toHex(sendBytes(data, challenge, SourceSyncTransferOptions.OFF)))
        assertEquals(expected, toHex(SourceSyncTransfer.encode(data, challenge)))
    }

    @Test
    fun authentication_off_still_ignores_a_trailing_tag_frame() {
        val data = envelope("envelope64")
        val challenge = nonce("first")
        val frames = SourceSyncTransfer.encode(data, challenge)
        val trailing = frames + frame(byteArrayOf(SourceSyncTransferAuthenticator.TAG_FRAME_KIND.toByte()) + ByteArray(32))
        val decoded = okOrFail(SourceSyncTransfer.decode(trailing, challenge), "带尾随 tag 帧的旧路径")
        assertArrayEquals(data, decoded.envelope)
        // 收满即返回：尾随的 tag 帧连读都不读，帧数与干净流相同。
        assertEquals(SourceSyncTransfer.chunkCount(data.size) + 1, decoded.frameCount)
    }

    @Test
    fun authentication_on_appends_exactly_one_tag_frame_after_the_last_chunk() {
        val data = envelope("envelope9000")
        val challenge = nonce("second")
        val key = secret("good")
        val stream = sendBytes(data, challenge, SourceSyncTransferOptions.withSecret(key))
        val frames = splitFrames(stream)
        assertEquals(SourceSyncTransfer.chunkCount(data.size) + 2, frames.size)
        val last = frames.last()
        assertEquals(SourceSyncTransferAuthenticator.TAG_FRAME_KIND, last[0].toInt() and 0xFF)
        assertEquals(SourceSyncTransferAuthenticator.TAG_FRAME_BYTES, last.size)
        assertArrayEquals(
            "tag 帧的载荷必须就是 createTag 的结果",
            SourceSyncTransferAuthenticator.createTag(key, challenge, data),
            last.copyOfRange(1, last.size),
        )
    }

    @Test
    fun a_local_round_trip_under_the_same_secret_succeeds() {
        val data = envelope("envelope64")
        val challenge = nonce("third")
        val key = secret("good")
        val stream = sendBytes(data, challenge, SourceSyncTransferOptions.withSecret(key))
        val decoded = okOrFail(
            SourceSyncTransfer.decode(stream, challenge, SourceSyncTransferOptions.withSecret(key, SourceSyncNonceCache(), "device-a")),
            "同口令回环",
        )
        assertArrayEquals(data, decoded.envelope)
        assertEquals(challenge, decoded.challenge)
    }

    // ------------------------------------------------------------------ 失败不烧 nonce

    @Test
    fun a_wrong_secret_does_not_burn_the_nonce_so_the_real_transfer_still_works() {
        val data = envelope("envelope64")
        val challenge = nonce("first")
        val good = secret("good")
        val wrong = secret("wrong")
        val cache = SourceSyncNonceCache()
        val receiving = SourceSyncTransferOptions.withSecret(good, cache, "device-a")

        // 攻击者用错口令伪造一封信封：必须 auth-mismatch，且不记 nonce。
        val forged = sendBytes(data, challenge, SourceSyncTransferOptions.withSecret(wrong))
        assertEquals("auth-mismatch", failureOf(SourceSyncTransfer.decode(forged, challenge, receiving)).reason)
        assertEquals("失配不许记 nonce", 0, cache.count)

        // 随后合法传输用同一个 nonce：必须成功，而不是被当成重放。
        val legit = sendBytes(data, challenge, SourceSyncTransferOptions.withSecret(good))
        val decoded = okOrFail(SourceSyncTransfer.decode(legit, challenge, receiving), "真正的那次传输")
        assertArrayEquals(data, decoded.envelope)
        assertEquals("成功后 nonce 才被记下", 1, cache.count)
    }

    @Test
    fun a_tampered_envelope_is_a_mismatch_and_a_second_tag_frame_is_never_read() {
        val data = envelope("envelope64")
        val challenge = nonce("first")
        val key = secret("good")
        val stream = sendBytes(data, challenge, SourceSyncTransferOptions.withSecret(key))
        val flipped = stream.copyOf()
        // 改最后一个数据块的最后一个字节（tag 前的那个字节）。
        flipped[stream.size - SourceSyncTransferAuthenticator.TAG_FRAME_BYTES - 5] =
            (flipped[stream.size - SourceSyncTransferAuthenticator.TAG_FRAME_BYTES - 5].toInt() xor 0x01).toByte()
        assertEquals(
            "auth-mismatch",
            failureOf(SourceSyncTransfer.decode(flipped, challenge, SourceSyncTransferOptions.withSecret(key))).reason,
        )

        // 第一个有效 tag 就返回：后面再塞一帧 tag 不会被读。
        val twoTags = stream + frame(byteArrayOf(SourceSyncTransferAuthenticator.TAG_FRAME_KIND.toByte()) + ByteArray(32))
        val decoded = okOrFail(
            SourceSyncTransfer.decode(twoTags, challenge, SourceSyncTransferOptions.withSecret(key, SourceSyncNonceCache())),
            "两个 tag 帧",
        )
        assertArrayEquals(data, decoded.envelope)
        assertEquals("第二个 tag 帧不该被读", SourceSyncTransfer.chunkCount(data.size) + 2, decoded.frameCount)
    }

    @Test
    fun a_send_with_authentication_but_no_secret_writes_nothing() {
        val output = ByteArrayOutputStream()
        val failure = failureOf(SourceSyncTransfer.sendTo(output, envelope("envelope64"), nonce("first"), SourceSyncTransferOptions.withSecret(null)))
        assertEquals("auth-secret-unavailable", failure.reason)
        assertEquals(0, output.size())
    }

    @Test
    fun a_send_with_authentication_but_a_blank_challenge_writes_nothing() {
        for (challenge in listOf<String?>(null, "", "   ")) {
            val output = ByteArrayOutputStream()
            val failure = failureOf(SourceSyncTransfer.sendTo(output, envelope("envelope64"), challenge, SourceSyncTransferOptions.withSecret(secret("good"))))
            assertEquals("挑战为 " + challenge + " 时必须 auth-required", "auth-required", failure.reason)
            assertEquals("auth-required 时一个字节都不写", 0, output.size())
        }
    }

    @Test
    fun a_receive_with_authentication_but_no_secret_reads_nothing() {
        var reads = 0
        val counting = object : InputStream() {
            override fun read(): Int {
                reads++
                return -1
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++
                return -1
            }
        }
        val failure = failureOf(SourceSyncTransfer.receive(counting, null, SourceSyncTransferOptions.withSecret(null)))
        assertEquals("auth-secret-unavailable", failure.reason)
        assertEquals("没有口令时不该读对端一个字节", 0, reads)
    }

    // ------------------------------------------------------------------ nonce 缓存

    @Test
    fun the_nonce_cache_evicts_the_oldest_entry_first() {
        val cache = SourceSyncNonceCache(2)
        assertTrue(cache.accept("device-a", "n1"))
        assertTrue(cache.accept("device-a", "n2"))
        assertFalse(cache.accept("device-a", "n1"))
        assertTrue("容量 2 时第三条会把最旧的挤掉", cache.accept("device-a", "n3"))
        assertEquals(2, cache.count)
        assertTrue("被淘汰的那条重新算首次", cache.accept("device-a", "n1"))
        assertFalse(cache.accept("device-a", "n3"))
        cache.clear()
        assertEquals(0, cache.count)
        assertTrue("clear 之后都算首次", cache.accept("device-a", "n3"))
    }

    @Test
    fun the_nonce_cache_partitions_entries_by_device_id() {
        val cache = SourceSyncNonceCache()
        assertTrue(cache.accept("device-a", "same-nonce"))
        assertTrue("不同设备碰巧同 nonce 不许互相顶掉", cache.accept("device-b", "same-nonce"))
        assertTrue("deviceId 为 null 是独立分区", cache.accept(null, "same-nonce"))
        assertFalse(cache.accept("device-a", "same-nonce"))
        assertFalse(cache.accept(null, "same-nonce"))
        assertEquals(3, cache.count)
    }

    @Test
    fun the_nonce_cache_rejects_an_empty_nonce_and_a_zero_capacity() {
        try {
            SourceSyncNonceCache(0)
            fail("容量 0 必须抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("one nonce"))
        }
        val cache = SourceSyncNonceCache(1)
        try {
            cache.accept("device-a", "")
            fail("空 nonce 必须抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("never empty"))
        }
    }

    @Test
    fun create_nonce_is_twenty_two_base64url_characters_and_fresh_each_time() {
        val first = SourceSyncTransferAuthenticator.createNonce()
        val second = SourceSyncTransferAuthenticator.createNonce()
        assertEquals(22, first.length)
        assertTrue("只允许 base64url 字符：" + first, Regex("^[A-Za-z0-9_-]+$").matches(first))
        assertNotEquals("两次必须不同", first, second)
        assertEquals(16, java.util.Base64.getUrlDecoder().decode(first + "==".substring(2)).size)
    }

    // ------------------------------------------------------------------ 助手

    private fun optionsOf(record: JsonObject, cache: SourceSyncNonceCache): SourceSyncTransferOptions? {
        if (!record.getValue("authenticate").jsonPrimitive.content.toBoolean()) {
            return null
        }
        val spec = record.getValue("secret").jsonPrimitive.content
        val deviceId = nullableString(record["deviceId"])
        return when (spec) {
            "absent" -> SourceSyncTransferOptions.withSecret(null, cache, deviceId)
            "off" -> null
            else -> SourceSyncTransferOptions.withSecret(secret(spec), cache, deviceId)
        }
    }

    private fun sendBytes(data: ByteArray, challenge: String?, options: SourceSyncTransferOptions?): ByteArray {
        val output = ByteArrayOutputStream()
        okOrFail(SourceSyncTransfer.sendTo(output, data, challenge, options), "发送")
        return output.toByteArray()
    }

    private fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = ((payload.size ushr 24) and 0xFF).toByte()
        out[1] = ((payload.size ushr 16) and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    private fun splitFrames(stream: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var cursor = 0
        while (cursor + 4 <= stream.size) {
            val length = ((stream[cursor].toInt() and 0xFF) shl 24) or
                ((stream[cursor + 1].toInt() and 0xFF) shl 16) or
                ((stream[cursor + 2].toInt() and 0xFF) shl 8) or
                (stream[cursor + 3].toInt() and 0xFF)
            require(length in 1..8192) { "非法帧长 " + length }
            val payload = ByteArray(length)
            System.arraycopy(stream, cursor + 4, payload, 0, length)
            frames.add(payload)
            cursor += 4 + length
        }
        require(cursor == stream.size) { "帧流尾部有残留 " + (stream.size - cursor) + " 字节" }
        return frames
    }
}
