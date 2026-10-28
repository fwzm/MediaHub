package com.mediahub.core.common.sync

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与 Windows 真实帧流的互操作对照（不是手写夹具）。
 *
 * 资源 windows-transfer-vectors.json 由 D:/mh-verify/transfer-vectors（临时 console 工程，不入库）
 * 直接调 D:/mh-w6/windows/src/MediaHub.Sync/SourceSyncTransfer 生成：
 * - vectors.withChallenge / withoutChallenge / goldenWithTrailingFrame：真实 device-a 信封的帧流；
 * - vectors.threeChunks：16466 字节合成信封（3 块），用来钉住「多块」这一路径；
 * - errors：19 条坏帧流，每条带 Windows 亲口给出的稳定码。
 *
 * 对照口径：码名逐字相等（不是「同义」）；字节逐字节相等（hex 全等）；错误向量必须先对 reason。
 */
class SourceSyncTransferVectorsTest {

    private val vectors by lazy {
        Json.parseToJsonElement(resource("windows-transfer-vectors.json")).jsonObject
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/sync/" + name)) { "缺少测试资源 /sync/" + name }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun resourceBytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/sync/" + name)) { "缺少测试资源 /sync/" + name }
            .use { it.readBytes() }

    private fun vector(name: String): JsonObject = vectors.getValue("vectors").jsonObject.getValue(name).jsonObject

    private fun frozenChallenge(): String = vectors.getValue("frozenChallenge").jsonPrimitive.content

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

    private fun sha256(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun okOrFail(result: SourceSyncTransferResult, what: String): SourceSyncTransferResult.Ok {
        if (result is SourceSyncTransferResult.Failed) {
            throw AssertionError(what + " 本应成功，实际失败：" + result.reason + " / " + result.message)
        }
        return result as SourceSyncTransferResult.Ok
    }

    // ------------------------------------------------------------------ 常量与码表

    @Test
    fun protocol_constants_match_the_windows_transfer_contract() {
        val constants = vectors.getValue("constants").jsonObject
        assertEquals(SourceSyncTransfer.MAXIMUM_ENVELOPE_BYTES, constants.getValue("maximumEnvelopeBytes").jsonPrimitive.int)
        assertEquals(SourceSyncTransfer.CHUNK_PAYLOAD_BYTES, constants.getValue("chunkPayloadBytes").jsonPrimitive.int)
        assertEquals(SourceSyncTransfer.CHUNK_HEADER_BYTES, constants.getValue("chunkHeaderBytes").jsonPrimitive.int)
        assertEquals(SourceSyncTransfer.CHALLENGE_FRAME_KIND, constants.getValue("challengeFrameKind").jsonPrimitive.int)
        assertEquals(SourceSyncTransfer.CHUNK_FRAME_KIND, constants.getValue("chunkFrameKind").jsonPrimitive.int)
        assertEquals(SourceSyncTransfer.MAXIMUM_CHALLENGE_BYTES, constants.getValue("maximumChallengeBytes").jsonPrimitive.int)
        // 帧上限来自配对契约：同步传输没有自己的帧上限，8192 是同一份常量。
        assertEquals(com.mediahub.core.common.pairing.PairingFrame.MAXIMUM_PAYLOAD_BYTES, constants.getValue("maximumFramePayloadBytes").jsonPrimitive.int)
        assertEquals(8 * 1024 * 1024, SourceSyncTransfer.MAXIMUM_ENVELOPE_BYTES)
    }

    @Test
    fun reason_vocabulary_is_identical_and_in_the_same_order_as_windows() {
        val expected = vectors.getValue("constants").jsonObject.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expected, SourceSyncTransferReasons.ALL)
        assertEquals(10, SourceSyncTransferReasons.ALL.size)
    }

    // ------------------------------------------------------------------ 解码 Windows 帧流

    @Test
    fun golden_vector_decodes_to_exactly_the_bytes_windows_sent() {
        val expected = resourceBytes("windows-envelope-device-a.json")
        val record = vector("withChallenge")
        val result = okOrFail(
            SourceSyncTransfer.decode(hex(record.getValue("framesHex").jsonPrimitive.content), frozenChallenge()),
            "带挑战的黄金帧流",
        )
        assertEquals(record.getValue("frameCount").jsonPrimitive.int, result.frameCount)
        assertEquals(record.getValue("bytes").jsonPrimitive.int, result.envelope.size)
        assertEquals(expected.size, result.envelope.size)
        assertTrue("重组结果必须与 Windows 送出的信封逐字节相同", expected.contentEquals(result.envelope))
        assertEquals(record.getValue("envelopeSha256").jsonPrimitive.content, sha256(result.envelope))
        assertEquals(frozenChallenge(), result.challenge)
        assertEquals(2, result.frameCount)
    }

    @Test
    fun golden_vector_without_a_challenge_still_decodes_the_same_envelope() {
        val expected = resourceBytes("windows-envelope-device-a.json")
        val result = okOrFail(SourceSyncTransfer.decode(hex(vector("withoutChallenge").getValue("framesHex").jsonPrimitive.content)), "不带挑战的帧流")
        assertTrue(expected.contentEquals(result.envelope))
        assertNull("没有挑战帧时 challenge 必须是 null", result.challenge)
        assertEquals(1, result.frameCount)
    }

    @Test
    fun kotlin_encoder_reproduces_the_windows_frame_stream_byte_for_byte() {
        val envelope = resourceBytes("windows-envelope-device-a.json")
        val withChallenge = vector("withChallenge")
        val withoutChallenge = vector("withoutChallenge")
        assertEquals(
            withChallenge.getValue("framesHex").jsonPrimitive.content,
            toHex(SourceSyncTransfer.encode(envelope, frozenChallenge())),
        )
        assertEquals(
            withoutChallenge.getValue("framesHex").jsonPrimitive.content,
            toHex(SourceSyncTransfer.encode(envelope, null)),
        )
        assertEquals(SourceSyncTransfer.CHUNK_PAYLOAD_BYTES, 8183)
    }

    @Test
    fun three_chunk_vector_announces_every_offset_and_the_same_total() {
        val meta = vectors.getValue("syntheticEnvelope").jsonObject
        val bytes = meta.getValue("byteLength").jsonPrimitive.int
        val synthetic = ByteArray(bytes) { index -> ((index * 31 + 7) and 0xFF).toByte() }
        assertEquals(bytes, 8183 * 2 + 100)
        assertEquals(3, SourceSyncTransfer.chunkCount(bytes))
        assertEquals(meta.getValue("chunkCount").jsonPrimitive.int, SourceSyncTransfer.chunkCount(bytes))

        val record = vector("threeChunks")
        val stream = hex(record.getValue("framesHex").jsonPrimitive.content)
        val result = okOrFail(SourceSyncTransfer.decode(stream, frozenChallenge()), "三块帧流")
        assertTrue("3 块信封必须重组回同一个字节序列", synthetic.contentEquals(result.envelope))
        assertEquals(meta.getValue("sha256").jsonPrimitive.content, sha256(result.envelope))
        assertEquals(4, result.frameCount)

        // 逐块核对：帧流里每一块的 total/offset 都必须与 Windows 写出的一致。
        val chunks = meta.getValue("chunks").jsonArray
        assertEquals(3, chunks.size)
        val frames = splitFrames(stream)
        assertEquals(4, frames.size)
        assertEquals(SourceSyncTransfer.CHALLENGE_FRAME_KIND, frames[0][0].toInt() and 0xFF)
        for (index in chunks.indices) {
            val frame = frames[index + 1]
            val chunk = chunks[index].jsonObject
            assertEquals(SourceSyncTransfer.CHUNK_FRAME_KIND, frame[0].toInt() and 0xFF)
            assertEquals("第 " + index + " 块 total", chunk.getValue("total").jsonPrimitive.int, int32(frame, 1))
            assertEquals("第 " + index + " 块 offset", chunk.getValue("offset").jsonPrimitive.int, int32(frame, 5))
            assertEquals("第 " + index + " 块载荷长度", chunk.getValue("payloadLength").jsonPrimitive.int, frame.size - 9)
            assertEquals("第 " + index + " 块帧长", chunk.getValue("frameLength").jsonPrimitive.int, frame.size + 4)
        }
    }

    @Test
    fun frames_after_a_complete_envelope_are_never_read() {
        val expected = resourceBytes("windows-envelope-device-a.json")
        val trailing = vector("goldenWithTrailingFrame")
        val clean = vector("withChallenge")
        val result = okOrFail(
            SourceSyncTransfer.decode(hex(trailing.getValue("framesHex").jsonPrimitive.content), frozenChallenge()),
            "带尾随帧的帧流",
        )
        assertTrue(expected.contentEquals(result.envelope))
        // 收满即返回：多出来的那一帧连读都不读，所以帧数与干净帧流相同。
        assertEquals(clean.getValue("frameCount").jsonPrimitive.int, result.frameCount)
        assertTrue(
            "尾随帧确实存在",
            hex(trailing.getValue("framesHex").jsonPrimitive.content).size >
                hex(clean.getValue("framesHex").jsonPrimitive.content).size,
        )
    }

    // ------------------------------------------------------------------ 错误向量：码必须同名

    @Test
    fun every_error_vector_reports_the_reason_windows_reports() {
        val errors: JsonArray = vectors.getValue("errors").jsonArray
        assertEquals(19, errors.size)
        val mismatches = ArrayList<String>()
        for (element in errors) {
            val record = element.jsonObject
            val name = record.getValue("name").jsonPrimitive.content
            val expectedReason = record.getValue("reason").jsonPrimitive.content
            val expectedChallenge = nullableString(record["expectedChallenge"])
            val stream = hex(record.getValue("framesHex").jsonPrimitive.content)
            val result = SourceSyncTransfer.decode(stream, expectedChallenge)
            if (result is SourceSyncTransferResult.Ok) {
                mismatches.add(name + "：本端判成功，Windows 判 " + expectedReason)
                continue
            }
            val failed = result as SourceSyncTransferResult.Failed
            if (failed.reason != expectedReason) {
                mismatches.add(name + "：本端 " + failed.reason + "，Windows " + expectedReason)
            }
        }
        assertTrue("错误向量码不一致：" + mismatches.joinToString(" | "), mismatches.isEmpty())
    }

    @Test
    fun error_vector_reasons_cover_every_documented_bucket() {
        val reasons = vectors.getValue("errors").jsonArray.map { it.jsonObject.getValue("reason").jsonPrimitive.content }.toSet()
        assertEquals(setOf("frame-invalid", "frame-truncated", "payload-too-large", "challenge-mismatch"), reasons)
        // 这 4 个桶都是本端会真实产出的码；envelope-invalid / io-failed / not-loopback 由其它层负责。
        assertTrue(reasons.all { it in SourceSyncTransferReasons.ALL })
    }

    // ------------------------------------------------------------------ 与规划器串联

    @Test
    fun a_decoded_golden_envelope_plans_exactly_like_the_windows_cli() {
        val result = okOrFail(
            SourceSyncTransfer.decode(hex(vector("withChallenge").getValue("framesHex").jsonPrimitive.content), frozenChallenge()),
            "黄金帧流",
        )
        val json = String(result.envelope, Charsets.UTF_8)
        val envelope = SourceSyncCodec.decode(json)
        assertEquals("device-a", envelope.deviceId)
        val summary = SourceSyncSummary.fromPlan(SourceSyncPlanner.plan(envelope, emptyList()))
        assertEquals(2, summary.apply)
        assertEquals(1, summary.ignored)
        assertEquals(1, summary.unsupported)
        assertTrue(summary.hasChanges)
    }

    @Test
    fun the_reassembled_envelope_round_trips_through_the_sync_codec_unchanged() {
        val result = okOrFail(
            SourceSyncTransfer.decode(hex(vector("withChallenge").getValue("framesHex").jsonPrimitive.content), frozenChallenge()),
            "黄金帧流",
        )
        val text = String(result.envelope, Charsets.UTF_8)
        val first = SourceSyncCodec.decode(text)
        val second = SourceSyncCodec.decode(SourceSyncCodec.encode(first))
        assertEquals(first, second)
        assertEquals(vectors.getValue("envelope").jsonObject.getValue("sha256").jsonPrimitive.content, sha256(result.envelope))
    }

    private fun splitFrames(stream: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var cursor = 0
        while (cursor + 4 <= stream.size) {
            val length = int32(stream, cursor)
            require(length in 1..8192) { "非法帧长 " + length }
            val payload = ByteArray(length)
            System.arraycopy(stream, cursor + 4, payload, 0, length)
            frames.add(payload)
            cursor += 4 + length
        }
        require(cursor == stream.size) { "帧流尾部有残留 " + (stream.size - cursor) + " 字节" }
        return frames
    }

    private fun int32(source: ByteArray, start: Int): Int =
        ((source[start].toInt() and 0xFF) shl 24) or
            ((source[start + 1].toInt() and 0xFF) shl 16) or
            ((source[start + 2].toInt() and 0xFF) shl 8) or
            (source[start + 3].toInt() and 0xFF)
}
