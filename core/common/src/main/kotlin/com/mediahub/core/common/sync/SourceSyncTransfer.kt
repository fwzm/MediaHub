package com.mediahub.core.common.sync

import com.mediahub.core.common.pairing.FrameRead
import com.mediahub.core.common.pairing.PairingFrame
import com.mediahub.core.common.pairing.PairingFrameIo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * 分块信封传输的稳定失败码（与 Windows SourceSyncTransferReasons 逐字同名同义）。
 *
 * 这 10 个码是互操作契约的一部分：两端任一侧新增/改写码名，另一端就认不出来。
 * envelope-invalid 由信封层（SourceSyncCodec）负责，协议层自己从不产出它——
 * 本层只保证「字节按序到位」，不解释字节的含义。
 */
object SourceSyncTransferReasons {
    const val NOT_LOOPBACK: String = "not-loopback"
    const val LISTENER_FAILED: String = "listener-failed"
    const val CONNECT_FAILED: String = "connect-failed"
    const val FRAME_INVALID: String = "frame-invalid"
    const val FRAME_TRUNCATED: String = "frame-truncated"
    const val PAYLOAD_TOO_LARGE: String = "payload-too-large"
    const val CHALLENGE_MISMATCH: String = "challenge-mismatch"
    const val ENVELOPE_INVALID: String = "envelope-invalid"
    const val TRANSFER_TIMEOUT: String = "transfer-timeout"
    const val IO_FAILED: String = "io-failed"

    /** 全部码，顺序与 Windows 的 All 列表一致（顺序本身被测试钉住，用于发现单侧增删）。 */
    val ALL: List<String> = listOf(
        NOT_LOOPBACK,
        LISTENER_FAILED,
        CONNECT_FAILED,
        FRAME_INVALID,
        FRAME_TRUNCATED,
        PAYLOAD_TOO_LARGE,
        CHALLENGE_MISMATCH,
        ENVELOPE_INVALID,
        TRANSFER_TIMEOUT,
        IO_FAILED,
    )
}

/** 一次分块传输的结果：成功给完整信封字节，失败只给稳定码。 */
sealed interface SourceSyncTransferResult {
    /**
     * 成功。
     *
     * @param envelope 重组后的信封字节（与发送端送出的字节逐字节相同）。
     * @param frameCount 实际读/写的帧数，挑战帧也计数（Windows 的语义）。
     * @param challenge 对端声明（读）或本端送出（写）的挑战，已去首尾空白；没有挑战帧时为 null。
     */
    class Ok(
        val envelope: ByteArray,
        val frameCount: Int,
        val challenge: String?,
    ) : SourceSyncTransferResult

    class Failed(val reason: String, val message: String? = null) : SourceSyncTransferResult
}

/**
 * 分块信封传输协议（ADR-S001 的传输层，Windows 权威实现：MediaHub.Sync/SourceSyncTransfer.cs）。
 *
 * 帧形状（全部走 4 字节大端长度帧，上限 8192 字节/帧）：
 * - 挑战帧：[0x01][挑战的 UTF-8 字节]；
 * - 数据帧：[0x02][4 字节大端 total][4 字节大端 offset][数据]，数据最多 8183 字节；
 * - tag 帧：[0x03][HMAC-SHA256 tag 32 字节]——可选，只有调用方传了开启认证的 options 才出现，
 *   且必须紧跟最后一块数据之后（W6-25；认证关闭时这条分支一行都不执行，字节与 W6-22 全等）。
 *
 * 为什么带 total/offset 而不是「读到流结束」：单帧上限 8192 字节装不下信封，而
 * 「读到结束」无法区分「对端停了」和「对端挂了」；显式 total 让半截传输有稳定码 frame-truncated。
 *
 * 读端的判定次序与 Windows 逐条一致（顺序即语义，改动顺序会改变错误码）：
 * 帧截断 -> 挑战帧位置 -> 帧类型 -> 首帧即数据块（要求挑战时）-> 头-only 数据帧 ->
 * total 合法性 -> 上限 -> offset 合法性 -> 首块 offset==0 -> total 一致性 -> offset 连续性 ->
 * 越界 -> 收满即返回。
 */
object SourceSyncTransfer {

    /** 信封字节上限：8 MiB。超过它一律 payload-too-large，且在任何分配之前拒绝。 */
    const val MAXIMUM_ENVELOPE_BYTES: Int = 8 * 1024 * 1024

    /** 挑战帧的类型字节。 */
    const val CHALLENGE_FRAME_KIND: Int = 0x01

    /** 数据帧的类型字节。 */
    const val CHUNK_FRAME_KIND: Int = 0x02

    /** 数据帧头长度：类型 1 + total 4 + offset 4。 */
    const val CHUNK_HEADER_BYTES: Int = 9

    /** 单个数据帧能装的信封字节数：帧上限 8192 减帧头 9 = 8183。 */
    const val CHUNK_PAYLOAD_BYTES: Int = PairingFrame.MAXIMUM_PAYLOAD_BYTES - CHUNK_HEADER_BYTES

    /** 挑战文本的 UTF-8 字节上限。 */
    const val MAXIMUM_CHALLENGE_BYTES: Int = 512

    /** 信封 N 字节需要几块：向上取整；N<=0 时为 0。 */
    fun chunkCount(envelopeBytes: Int): Int {
        if (envelopeBytes <= 0) {
            return 0
        }
        return (envelopeBytes + CHUNK_PAYLOAD_BYTES - 1) / CHUNK_PAYLOAD_BYTES
    }

    /**
     * 把信封切成帧载荷（每项是一帧的载荷，还没加 4 字节长度头）。
     *
     * 参数非法属于调用方的编程错误，抛 IllegalArgumentException；来自对端的非法输入一律不抛，走 Failed。
     */
    fun encodeFrames(envelope: ByteArray, challenge: String? = null): List<ByteArray> {
        require(envelope.isNotEmpty()) { "信封至少要有 1 字节" }
        require(envelope.size <= MAXIMUM_ENVELOPE_BYTES) {
            "信封 " + envelope.size + " 字节超过上限 " + MAXIMUM_ENVELOPE_BYTES
        }
        val frames = ArrayList<ByteArray>(chunkCount(envelope.size) + 1)
        val trimmed = challenge?.trim()
        if (!trimmed.isNullOrEmpty()) {
            val challengeBytes = trimmed.toByteArray(StandardCharsets.UTF_8)
            require(challengeBytes.size <= MAXIMUM_CHALLENGE_BYTES) {
                "挑战 " + challengeBytes.size + " 字节超过上限 " + MAXIMUM_CHALLENGE_BYTES
            }
            val frame = ByteArray(1 + challengeBytes.size)
            frame[0] = CHALLENGE_FRAME_KIND.toByte()
            challengeBytes.copyInto(frame, 1)
            frames.add(frame)
        }
        var offset = 0
        while (offset < envelope.size) {
            val length = minOf(CHUNK_PAYLOAD_BYTES, envelope.size - offset)
            val frame = ByteArray(CHUNK_HEADER_BYTES + length)
            frame[0] = CHUNK_FRAME_KIND.toByte()
            writeInt32BigEndian(frame, 1, envelope.size)
            writeInt32BigEndian(frame, 5, offset)
            envelope.copyInto(frame, CHUNK_HEADER_BYTES, offset, offset + length)
            frames.add(frame)
            offset += length
        }
        return frames
    }

    /** 整段帧流（每帧 4 字节大端长度 + 载荷），与 Windows SendAsync 写出的字节逐字节相同。 */
    fun encode(envelope: ByteArray, challenge: String? = null): ByteArray {
        val output = ByteArrayOutputStream()
        for (frame in encodeFrames(envelope, challenge)) {
            PairingFrameIo.writeFrame(output, frame)
        }
        return output.toByteArray()
    }

    /**
     * 写一封信封到流里（对应 Windows 的 SendAsync）。
     *
     * 空信封 / 超上限 / 挑战过长都返回 payload-too-large 且一个字都不写；
     * 写失败统一 io-failed。成功时返回的 challenge 是去空白后的实际送出值。
     */
    fun sendTo(
        output: OutputStream,
        envelope: ByteArray,
        challenge: String? = null,
        options: SourceSyncTransferOptions? = null,
    ): SourceSyncTransferResult {
        if (envelope.isEmpty()) {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.PAYLOAD_TOO_LARGE,
                "信封至少要有 1 字节；什么都没有写出",
            )
        }
        if (envelope.size > MAXIMUM_ENVELOPE_BYTES) {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.PAYLOAD_TOO_LARGE,
                "信封 " + envelope.size + " 字节超过 " + MAXIMUM_ENVELOPE_BYTES + " 字节上限；什么都没有写出",
            )
        }
        val frames = try {
            encodeFrames(envelope, challenge)
        } catch (error: IllegalArgumentException) {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.PAYLOAD_TOO_LARGE,
                "挑战超过 " + MAXIMUM_CHALLENGE_BYTES + " 字节上限；什么都没有写出",
            )
        }
        val authentication = options?.takeIf { it.authenticate }
        var tagFrame: ByteArray? = null
        if (authentication != null) {
            if (!authentication.hasSecret) {
                return SourceSyncTransferResult.Failed(
                    SourceSyncTransferAuthReasons.AUTH_SECRET_UNAVAILABLE,
                    "Authentication is on but this process holds no secret; nothing was sent.",
                )
            }
            val nonce = challenge?.trim()
            if (nonce.isNullOrEmpty()) {
                return SourceSyncTransferResult.Failed(
                    SourceSyncTransferAuthReasons.AUTH_REQUIRED,
                    "Authentication is on but no challenge was announced; " +
                        "the challenge is the nonce the tag binds to, so nothing was sent.",
                )
            }
            tagFrame = SourceSyncTransferAuthenticator.createTagFrame(
                authentication.secretForAuthentication,
                nonce.toByteArray(StandardCharsets.UTF_8),
                envelope,
            )
        }
        var written = 0
        try {
            for (frame in frames) {
                PairingFrameIo.writeFrame(output, frame)
                written++
            }
            if (tagFrame != null) {
                PairingFrameIo.writeFrame(output, tagFrame)
                written++
            }
            output.flush()
        } catch (error: IOException) {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.IO_FAILED,
                "写到第 " + written + " 帧时失败：" + error.javaClass.simpleName,
            )
        }
        return SourceSyncTransferResult.Ok(envelope.copyOf(), written, challenge?.trim())
    }

    /** 从内存字节解码（测试与离线回放用）；语义与 receive 完全相同。 */
    fun decode(
        bytes: ByteArray,
        expectedChallenge: String? = null,
        options: SourceSyncTransferOptions? = null,
    ): SourceSyncTransferResult =
        receive(ByteArrayInputStream(bytes), expectedChallenge, options)

    /**
     * 从流里读一封信封（对应 Windows 的 ReceiveAsync）。
     *
     * 判定次序与 Windows 逐条一致（见类型注释）；收满即返回，尾随帧不再读——
     * 这条不是疏忽：多读一帧会把「对端多写」变成「本端阻塞」。
     *
     * 超时映射说明：Windows 在端点层用 15 秒总超时把静默对端折成 transfer-timeout，
     * 本端没有独立端点层，因此在这里就把 SocketTimeoutException 折成 transfer-timeout，
     * 两端的对外码因此保持一致；其它 IO 失败仍是 io-failed。
     */
    fun receive(
        input: InputStream,
        expectedChallenge: String? = null,
        options: SourceSyncTransferOptions? = null,
    ): SourceSyncTransferResult {
        val authentication = options?.takeIf { it.authenticate }
        if (authentication != null && !authentication.hasSecret) {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferAuthReasons.AUTH_SECRET_UNAVAILABLE,
                "Authentication is on but this process holds no secret to check the peer's tag with; nothing was read.",
            )
        }
        val expected = expectedChallenge?.trim() ?: ""
        val challengeRequired = expected.isNotEmpty()
        var assembled: ByteArray? = null
        var complete = false
        var total = -1
        var received = 0
        var frames = 0
        var offered: String? = null
        try {
            while (true) {
                val read = PairingFrameIo.readFrame(input, PairingFrame.MAXIMUM_PAYLOAD_BYTES)
                if (read is FrameRead.Rejected) {
                    if (authentication != null && complete) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferAuthReasons.AUTH_MISSING,
                            "The peer sent the whole envelope but no tag frame; nothing was accepted.",
                        )
                    }
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FRAME_TRUNCATED,
                        if (assembled == null) {
                            "对端没有送出完整的一帧：" + read.detail
                        } else {
                            "对端在 " + received + " / " + total + " 字节处停止：" + read.detail
                        },
                    )
                }
                val frame = (read as FrameRead.Payload).bytes
                frames++
                val kind = frame[0].toInt() and 0xFF
                if (kind == SourceSyncTransferAuthenticator.TAG_FRAME_KIND) {
                    if (authentication == null) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FRAME_INVALID,
                            "Unknown frame kind 0x03.",
                        )
                    }
                    if (assembled == null) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FRAME_INVALID,
                            "The tag frame arrived before the first chunk.",
                        )
                    }
                    if (!complete) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FRAME_INVALID,
                            "The tag frame arrived after " + received + " of " + total +
                                " bytes; it must follow the last chunk.",
                        )
                    }
                    if (frame.size != SourceSyncTransferAuthenticator.TAG_FRAME_BYTES) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FRAME_INVALID,
                            "A tag frame is " + frame.size + " bytes; an HMAC-SHA256 tag is " +
                                SourceSyncTransferAuthenticator.TAG_BYTES + " bytes plus the kind byte.",
                        )
                    }
                    if (offered.isNullOrEmpty()) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferAuthReasons.AUTH_MISSING,
                            "The peer sent a tag but announced no challenge for it to bind to; nothing was accepted.",
                        )
                    }
                    val tag = frame.copyOfRange(1, frame.size)
                    val matches = SourceSyncTransferAuthenticator.verifyTag(
                        authentication.secretForAuthentication,
                        offered.toByteArray(StandardCharsets.UTF_8),
                        assembled,
                        tag,
                    )
                    if (!matches) {
                        // nonce 刻意不在这里记：伪造信封不能让真 nonce 被烧掉，否则合法传输会被当成重放。
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferAuthReasons.AUTH_MISMATCH,
                            "The tag does not match this envelope and nonce under this secret; " +
                                "nothing was accepted and the nonce was not recorded.",
                        )
                    }
                    if (!authentication.activeNonces.accept(authentication.deviceId, offered)) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferAuthReasons.AUTH_REPLAYED,
                            "This device already accepted an envelope under this nonce; " +
                                "a replayed transfer is ignored and nothing was accepted.",
                        )
                    }
                    return SourceSyncTransferResult.Ok(assembled, frames, offered)
                }
                if (kind == CHALLENGE_FRAME_KIND) {
                    if (frames != 1) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FRAME_INVALID,
                            "挑战帧必须出现在第一块数据之前",
                        )
                    }
                    offered = String(frame, 1, frame.size - 1, StandardCharsets.UTF_8).trim()
                    if (offered.isEmpty()) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FRAME_INVALID,
                            "挑战帧里没有挑战",
                        )
                    }
                    if (challengeRequired && offered != expected) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.CHALLENGE_MISMATCH,
                            "对端给出的挑战与邀请不符；信封一个字节都没有读",
                        )
                    }
                    continue
                }
                if (kind != CHUNK_FRAME_KIND) {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FRAME_INVALID,
                        "未知帧类型 0x" + Integer.toHexString(kind),
                    )
                }
                if (complete) {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FRAME_INVALID,
                        "The peer sent another chunk after the envelope was complete; " +
                            "the tag frame must follow the last chunk.",
                    )
                }
                if (frames == 1 && challengeRequired) {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.CHALLENGE_MISMATCH,
                        "对端没有先声明邀请里的挑战；信封一个字节都没有读",
                    )
                }
                if (frame.size <= CHUNK_HEADER_BYTES) {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FRAME_INVALID,
                        "数据帧只有 " + frame.size + " 字节；只有帧头不带载荷",
                    )
                }
                val frameTotal = readInt32BigEndian(frame, 1)
                val frameOffset = readInt32BigEndian(frame, 5)
                val payloadLength = frame.size - CHUNK_HEADER_BYTES
                if (frameTotal <= 0) {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FRAME_INVALID,
                        "数据块声明的总长是 " + frameTotal + " 字节",
                    )
                }
                if (frameTotal > MAXIMUM_ENVELOPE_BYTES) {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.PAYLOAD_TOO_LARGE,
                        "对端声明 " + frameTotal + " 字节，超过 " + MAXIMUM_ENVELOPE_BYTES + " 字节上限；什么都没有缓存",
                    )
                }
                if (frameOffset < 0) {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FRAME_INVALID,
                        "数据块声明的偏移是负数",
                    )
                }
                if (assembled == null) {
                    total = frameTotal
                    if (frameOffset != 0) {
                        return SourceSyncTransferResult.Failed(
                            SourceSyncTransferReasons.FRAME_INVALID,
                            "第一块从 " + frameOffset + " 开始，而不是 0",
                        )
                    }
                    assembled = ByteArray(frameTotal)
                } else if (frameTotal != total) {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FRAME_INVALID,
                        "数据块声明 " + frameTotal + " 字节，之前是 " + total + " 字节",
                    )
                }
                if (frameOffset != received) {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FRAME_INVALID,
                        "数据块从 " + frameOffset + " 开始，但已经收到 " + received + " 字节",
                    )
                }
                if (received + payloadLength > total) {
                    return SourceSyncTransferResult.Failed(
                        SourceSyncTransferReasons.FRAME_INVALID,
                        "数据块越过声明的总长 " + total + " 字节",
                    )
                }
                frame.copyInto(assembled, received, CHUNK_HEADER_BYTES, frame.size)
                received += payloadLength
                if (received == total) {
                    if (authentication == null) {
                        return SourceSyncTransferResult.Ok(assembled, frames, offered)
                    }
                    // 认证开着时「收满」还不是成功：继续读 tag 帧；对端此时关流会落到 auth-missing。
                    complete = true
                }
            }
        } catch (timeout: java.net.SocketTimeoutException) {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.TRANSFER_TIMEOUT,
                "对端在超时前没有送完信封（已收 " + received + " 字节）",
            )
        } catch (error: IOException) {
            return SourceSyncTransferResult.Failed(
                SourceSyncTransferReasons.IO_FAILED,
                "读信封失败：" + error.javaClass.simpleName,
            )
        }
    }

    private fun writeInt32BigEndian(target: ByteArray, start: Int, value: Int) {
        target[start] = ((value ushr 24) and 0xFF).toByte()
        target[start + 1] = ((value ushr 16) and 0xFF).toByte()
        target[start + 2] = ((value ushr 8) and 0xFF).toByte()
        target[start + 3] = (value and 0xFF).toByte()
    }

    private fun readInt32BigEndian(source: ByteArray, start: Int): Int =
        ((source[start].toInt() and 0xFF) shl 24) or
            ((source[start + 1].toInt() and 0xFF) shl 16) or
            ((source[start + 2].toInt() and 0xFF) shl 8) or
            (source[start + 3].toInt() and 0xFF)
}
