package com.mediahub.core.common.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 本端自洽往返：解码器 + 编码器自己对着跑，钉住分块边界、挑战处理与各类失败码。
 *
 * 与 Windows 的逐字节对照在 SourceSyncTransferVectorsTest 里；这里不再重复外部向量，
 * 只补「边界值」与「Windows 侧难以造出来的流」（例如直接抛 IOException 的流）。
 */
class SourceSyncTransferRoundTripTest {

    private fun envelope(size: Int): ByteArray = ByteArray(size) { index -> ((index * 17 + 3) and 0xFF).toByte() }

    private fun okOrFail(result: SourceSyncTransferResult, what: String): SourceSyncTransferResult.Ok {
        if (result is SourceSyncTransferResult.Failed) {
            throw AssertionError(what + " 本应成功，实际失败：" + result.reason + " / " + result.message)
        }
        return result as SourceSyncTransferResult.Ok
    }

    private fun reasonOf(result: SourceSyncTransferResult): String =
        (result as? SourceSyncTransferResult.Failed)?.reason ?: fail("本应失败却成功了") as String

    private fun failureOf(result: SourceSyncTransferResult): SourceSyncTransferResult.Failed {
        if (result is SourceSyncTransferResult.Ok) {
            throw AssertionError("本应失败却成功了（" + result.envelope.size + " 字节 / " + result.frameCount + " 帧）")
        }
        return result as SourceSyncTransferResult.Failed
    }

    // ------------------------------------------------------------------ 分块边界

    @Test
    fun chunk_count_boundaries_match_the_payload_ceiling() {
        assertEquals(0, SourceSyncTransfer.chunkCount(0))
        assertEquals(0, SourceSyncTransfer.chunkCount(-1))
        assertEquals(1, SourceSyncTransfer.chunkCount(1))
        assertEquals(1, SourceSyncTransfer.chunkCount(8182))
        assertEquals(1, SourceSyncTransfer.chunkCount(8183))
        assertEquals(2, SourceSyncTransfer.chunkCount(8184))
        assertEquals(2, SourceSyncTransfer.chunkCount(16366))
        assertEquals(3, SourceSyncTransfer.chunkCount(16367))
        // 8 MiB 上限处的块数：ceil(8388608 / 8183) = 1026。
        assertEquals(1026, SourceSyncTransfer.chunkCount(SourceSyncTransfer.MAXIMUM_ENVELOPE_BYTES))
    }

    @Test
    fun round_trip_survives_every_chunk_boundary() {
        val sizes = listOf(1, 100, 8182, 8183, 8184, 16366, 16367)
        for (size in sizes) {
            val bytes = envelope(size)
            for (challenge in listOf<String?>(null, "invite-42")) {
                val frames = SourceSyncTransfer.encode(bytes, challenge)
                val decoded = okOrFail(SourceSyncTransfer.decode(frames, challenge), "尺寸 " + size + " / 挑战 " + challenge)
                assertArrayEquals("尺寸 " + size + " 往返后必须逐字节相同", bytes, decoded.envelope)
                assertEquals(
                    "尺寸 " + size + " 的帧数",
                    SourceSyncTransfer.chunkCount(size) + if (challenge == null) 0 else 1,
                    decoded.frameCount,
                )
                assertEquals(challenge, decoded.challenge)
            }
        }
    }

    @Test
    fun a_blank_challenge_sends_no_challenge_frame() {
        val bytes = envelope(5000)
        assertEquals(SourceSyncTransfer.chunkCount(5000), SourceSyncTransfer.encodeFrames(bytes, "   ").size)
        val decoded = okOrFail(SourceSyncTransfer.decode(SourceSyncTransfer.encode(bytes, "  ")), "空白挑战")
        assertArrayEquals(bytes, decoded.envelope)
        assertNull("空白挑战不需要对端回执", decoded.challenge)
        // 期望值也是空白时，等于「不要求挑战」，不能因此判 challenge-mismatch。
        val tolerant = okOrFail(SourceSyncTransfer.decode(SourceSyncTransfer.encode(bytes, null), "   "), "期望空白挑战")
        assertArrayEquals(bytes, tolerant.envelope)
    }

    @Test
    fun the_challenge_is_trimmed_on_both_ends() {
        val bytes = envelope(64)
        val sent = okOrFail(
            SourceSyncTransfer.sendTo(ByteArrayOutputStream(), bytes, "  invite-9  "),
            "带空白的挑战",
        )
        assertEquals("invite-9", sent.challenge)
        val stream = SourceSyncTransfer.encode(bytes, "  invite-9  ")
        val decoded = okOrFail(SourceSyncTransfer.decode(stream, " invite-9 "), "两端空白")
        assertEquals("invite-9", decoded.challenge)
        assertEquals(SourceSyncTransferReasons.CHALLENGE_MISMATCH, reasonOf(SourceSyncTransfer.decode(stream, "invite-10")))
    }

    // ------------------------------------------------------------------ 写出前就该失败

    @Test
    fun an_empty_envelope_fails_before_a_single_byte_is_written() {
        val output = ByteArrayOutputStream()
        val failure = failureOf(SourceSyncTransfer.sendTo(output, ByteArray(0), "invite"))
        assertEquals(SourceSyncTransferReasons.PAYLOAD_TOO_LARGE, failure.reason)
        assertEquals("失败时不许写出任何字节", 0, output.size())
        try {
            SourceSyncTransfer.encode(ByteArray(0))
            fail("空信封编码必须抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("信封"))
        }
    }

    @Test
    fun an_envelope_one_byte_over_the_ceiling_fails_before_a_single_byte_is_written() {
        val oversized = ByteArray(SourceSyncTransfer.MAXIMUM_ENVELOPE_BYTES + 1)
        val output = ByteArrayOutputStream()
        val failure = failureOf(SourceSyncTransfer.sendTo(output, oversized))
        assertEquals(SourceSyncTransferReasons.PAYLOAD_TOO_LARGE, failure.reason)
        assertEquals(0, output.size())
        try {
            SourceSyncTransfer.encode(oversized)
            fail("超上限信封编码必须抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("上限"))
        }
    }

    @Test
    fun challenge_length_ceiling_is_enforced_before_writing() {
        val bytes = envelope(32)
        val tooLong = "x".repeat(SourceSyncTransfer.MAXIMUM_CHALLENGE_BYTES + 1)
        val output = ByteArrayOutputStream()
        val failure = failureOf(SourceSyncTransfer.sendTo(output, bytes, tooLong))
        assertEquals(SourceSyncTransferReasons.PAYLOAD_TOO_LARGE, failure.reason)
        assertEquals(0, output.size())
        try {
            SourceSyncTransfer.encode(bytes, tooLong)
            fail("超长挑战编码必须抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("挑战"))
        }
        val atLimit = "y".repeat(SourceSyncTransfer.MAXIMUM_CHALLENGE_BYTES)
        val accepted = okOrFail(SourceSyncTransfer.sendTo(ByteArrayOutputStream(), bytes, atLimit), "恰好到上限的挑战")
        assertEquals(atLimit, accepted.challenge)
        assertEquals(2, accepted.frameCount)
    }

    // ------------------------------------------------------------------ 解码侧的坏流

    @Test
    fun bytes_after_a_complete_envelope_are_ignored() {
        val bytes = envelope(9000)
        val clean = SourceSyncTransfer.encode(bytes, "invite-1")
        val noisy = clean + ByteArray(64) { 0x5A }
        val decoded = okOrFail(SourceSyncTransfer.decode(noisy, "invite-1"), "带尾随垃圾的流")
        assertArrayEquals(bytes, decoded.envelope)
        assertEquals(3, decoded.frameCount)
    }

    @Test
    fun truncated_streams_report_frame_truncated() {
        val empty = failureOf(SourceSyncTransfer.decode(ByteArray(0)))
        assertEquals(SourceSyncTransferReasons.FRAME_TRUNCATED, empty.reason)
        assertTrue("首帧都没收到时必须说明这一点：" + empty.message, empty.message!!.contains("完整的一帧"))

        val headerOnly = failureOf(SourceSyncTransfer.decode(byteArrayOf(0, 0, 1)))
        assertEquals(SourceSyncTransferReasons.FRAME_TRUNCATED, headerOnly.reason)

        val bytes = envelope(20000)
        val stream = SourceSyncTransfer.encode(bytes, "invite-1")
        val cutMidBody = stream.copyOf(stream.size - 40)
        val midBody = failureOf(SourceSyncTransfer.decode(cutMidBody, "invite-1"))
        assertEquals(SourceSyncTransferReasons.FRAME_TRUNCATED, midBody.reason)
        assertTrue("已收到部分数据时必须报出进度：" + midBody.message, midBody.message!!.contains("字节处停止"))
    }

    @Test
    fun io_failures_map_to_io_failed() {
        val brokenInput = object : InputStream() {
            override fun read(): Int = throw IOException("读到一半没了")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("读到一半没了")
        }
        val readFailure = failureOf(SourceSyncTransfer.receive(brokenInput))
        assertEquals(SourceSyncTransferReasons.IO_FAILED, readFailure.reason)

        val brokenOutput = object : OutputStream() {
            override fun write(value: Int) = throw IOException("写不进去")
            override fun write(buffer: ByteArray, offset: Int, length: Int) = throw IOException("写不进去")
        }
        val writeFailure = failureOf(SourceSyncTransfer.sendTo(brokenOutput, envelope(100)))
        assertEquals(SourceSyncTransferReasons.IO_FAILED, writeFailure.reason)
        assertTrue("失败点要能定位：" + writeFailure.message, writeFailure.message!!.contains("0 帧"))
    }

    @Test
    fun a_stream_that_omits_the_required_challenge_frame_is_a_mismatch() {
        val stream = SourceSyncTransfer.encode(envelope(100), null)
        val failure = failureOf(SourceSyncTransfer.decode(stream, "invite-1"))
        assertEquals(SourceSyncTransferReasons.CHALLENGE_MISMATCH, failure.reason)
        assertTrue(failure.message!!.contains("挑战"))
    }

    @Test
    fun a_second_challenge_frame_is_frame_invalid() {
        val stream = concat(
            frame(challengeFrame("invite-1")),
            frame(challengeFrame("invite-1")),
            frame(chunkFrame(4, 0, ByteArray(4) { 1 })),
        )
        val failure = failureOf(SourceSyncTransfer.decode(stream, "invite-1"))
        assertEquals(SourceSyncTransferReasons.FRAME_INVALID, failure.reason)
        assertTrue(failure.message!!.contains("第一块数据之前"))
    }

    @Test
    fun unknown_frame_kinds_are_frame_invalid() {
        val failure = failureOf(SourceSyncTransfer.decode(frame(byteArrayOf(0x7F.toByte(), 1, 2, 3)), null))
        assertEquals(SourceSyncTransferReasons.FRAME_INVALID, failure.reason)
        assertTrue(failure.message!!.contains("0x7f"))
    }

    @Test
    fun chunk_frames_without_payload_or_with_zero_total_are_frame_invalid() {
        val headerOnly = failureOf(SourceSyncTransfer.decode(frame(chunkFrame(10, 0, ByteArray(0))), null))
        assertEquals(SourceSyncTransferReasons.FRAME_INVALID, headerOnly.reason)

        val zeroTotal = failureOf(SourceSyncTransfer.decode(frame(chunkFrame(0, 0, ByteArray(4))), null))
        assertEquals(SourceSyncTransferReasons.FRAME_INVALID, zeroTotal.reason)

        val firstChunkNotAtZero = failureOf(SourceSyncTransfer.decode(frame(chunkFrame(16, 4, ByteArray(12))), null))
        assertEquals(SourceSyncTransferReasons.FRAME_INVALID, firstChunkNotAtZero.reason)
        assertTrue("要指出首块没有从 0 开始：" + firstChunkNotAtZero.message, firstChunkNotAtZero.message!!.contains("第一块"))
    }

    @Test
    fun a_ceiling_above_the_envelope_limit_is_payload_too_large() {
        val declared = frame(chunkFrame(SourceSyncTransfer.MAXIMUM_ENVELOPE_BYTES + 1, 0, ByteArray(10)))
        val failure = failureOf(SourceSyncTransfer.decode(declared, null))
        assertEquals(SourceSyncTransferReasons.PAYLOAD_TOO_LARGE, failure.reason)
    }

    @Test
    fun decode_and_receive_agree_on_the_same_bytes() {
        val stream = SourceSyncTransfer.encode(envelope(12345), "invite-7")
        val viaBytes = okOrFail(SourceSyncTransfer.decode(stream, "invite-7"), "decode")
        val viaStream = okOrFail(SourceSyncTransfer.receive(ByteArrayInputStream(stream), "invite-7"), "receive")
        assertArrayEquals(viaBytes.envelope, viaStream.envelope)
        assertEquals(viaBytes.frameCount, viaStream.frameCount)
        assertEquals(viaBytes.challenge, viaStream.challenge)
    }

    @Test
    fun a_read_timeout_on_the_stream_is_reported_as_transfer_timeout() {
        val timingOut = object : InputStream() {
            override fun read(): Int = throw SocketTimeoutException("读超时")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw SocketTimeoutException("读超时")
        }
        assertEquals(SourceSyncTransferReasons.TRANSFER_TIMEOUT, reasonOf(SourceSyncTransfer.receive(timingOut)))
    }

    // ------------------------------------------------------------------ 帧构造助手（不经生产编码器）

    private fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = ((payload.size ushr 24) and 0xFF).toByte()
        out[1] = ((payload.size ushr 16) and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        return out
    }

    private fun challengeFrame(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + body.size)
        payload[0] = SourceSyncTransfer.CHALLENGE_FRAME_KIND.toByte()
        System.arraycopy(body, 0, payload, 1, body.size)
        return payload
    }

    private fun chunkFrame(total: Int, offset: Int, data: ByteArray): ByteArray {
        val payload = ByteArray(SourceSyncTransfer.CHUNK_HEADER_BYTES + data.size)
        payload[0] = SourceSyncTransfer.CHUNK_FRAME_KIND.toByte()
        payload[1] = ((total ushr 24) and 0xFF).toByte()
        payload[2] = ((total ushr 16) and 0xFF).toByte()
        payload[3] = ((total ushr 8) and 0xFF).toByte()
        payload[4] = (total and 0xFF).toByte()
        payload[5] = ((offset ushr 24) and 0xFF).toByte()
        payload[6] = ((offset ushr 16) and 0xFF).toByte()
        payload[7] = ((offset ushr 8) and 0xFF).toByte()
        payload[8] = (offset and 0xFF).toByte()
        System.arraycopy(data, 0, payload, 9, data.size)
        return payload
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }
}
