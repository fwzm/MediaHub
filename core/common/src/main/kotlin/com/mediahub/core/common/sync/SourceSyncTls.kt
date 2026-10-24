package com.mediahub.core.common.sync

import com.mediahub.core.common.pairing.LoopbackOnly
import com.mediahub.core.common.pairing.PairingFingerprint
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 传输的稳定失败码（与 Windows SourceSyncTlsReasons 逐字同名、同序、同义）。
 *
 * 这 4 个码是互操作契约的一部分，且与传输层 10 码、认证层 5 码互不相交：
 * 看到 tls- 前缀就知道问题出在「记录层没谈成」，而不是帧或 tag。
 * 诊断文案不是契约（两侧各自本地化），码是。
 */
object SourceSyncTlsReasons {
    /** 握手没谈成：对端不是 TLS 1.3、握手中断、或本机没有 TLS 1.3。 */
    const val HANDSHAKE_FAILED: String = "tls-handshake-failed"

    /** 谈成了，但证书公钥指纹与钉住的不符；此时一个信封字节都没有读写。 */
    const val FINGERPRINT_MISMATCH: String = "tls-fingerprint-mismatch"

    /** 没有可用的证书或指纹（本端无证书、对端无证书、指纹不是 64 位 hex）。 */
    const val CERTIFICATE_UNAVAILABLE: String = "tls-certificate-unavailable"

    /** 对端用 TLS 记录开连接，而本端端点是明文的。 */
    const val NOT_ENABLED: String = "tls-not-enabled"

    /** 全部码，顺序与 Windows 的 All 列表一致（顺序本身被测试钉住，用于发现单侧增删）。 */
    val ALL: List<String> = listOf(
        HANDSHAKE_FAILED,
        FINGERPRINT_MISMATCH,
        CERTIFICATE_UNAVAILABLE,
        NOT_ENABLED,
    )
}

/**
 * TLS 选项：默认关闭，开启时把对端钉在一枚 SHA-256(SPKI) 指纹上。
 *
 * 与 Windows SourceSyncTlsOptions 同形（sealed class，不是 record/toString 默认实现），
 * 因为默认 toString 会把指纹打进日志；这里的 toString 只说明「开没开」，不印指纹。
 */
sealed class SourceSyncTlsOptions {

    /** 是否开启 TLS。 */
    abstract val enabled: Boolean

    /** 期望的对端指纹（原样保存，可能不是合法指纹；合法性由握手判定）。 */
    abstract val expectedFingerprint: String?

    /** 关闭 TLS：字节与 W6-22 至今完全一致，客户端不会多说一个字节。 */
    object Off : SourceSyncTlsOptions() {
        override val enabled: Boolean get() = false
        override val expectedFingerprint: String? get() = null

        override fun toString(): String = "SourceSyncTlsOptions { Enabled = false }"
    }

    /**
     * 开启 TLS 并钉住指纹。
     *
     * @param pin 64 位 hex（大小写不敏感）；不合法的指纹不在这里抛，握手时判为
     *   [SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE]——与 Windows 一致，构造不校验。
     */
    class Pinned(pin: String) : SourceSyncTlsOptions() {
        override val enabled: Boolean get() = true
        override val expectedFingerprint: String = pin

        /** 解析后的指纹；不合法时为 null。 */
        val fingerprint: PairingFingerprint? = PairingFingerprint.tryParse(pin)

        override fun toString(): String = "SourceSyncTlsOptions { Enabled = true, ExpectedFingerprint = <redacted> }"
    }

    /** 有可用指纹（Windows 的 HasPin）。 */
    val hasPin: Boolean
        get() = this is Pinned && fingerprint != null

    companion object {
        /** 有指纹就钉住它，空白给一个不可用的 Pinned（握手会报 certificate-unavailable，而不是悄悄降级成明文）。 */
        fun pinned(pin: String?): SourceSyncTlsOptions =
            if (pin.isNullOrBlank()) Pinned("") else Pinned(pin)
    }
}

/** 握手成功后的会话：信封走 [input]/[output]，[certificateFingerprint] 是对端（服务端模式是本端）的公钥指纹。 */
class SourceSyncTlsSession internal constructor(
    val socket: SSLSocket,
    val input: InputStream,
    val output: OutputStream,
    val certificateFingerprint: String,
) {
    override fun toString(): String = "SourceSyncTlsSession(" + certificateFingerprint + ")"
}

/** 一次 TLS 握手的结局：成功给会话，失败只给稳定码与诊断。 */
sealed interface SourceSyncTlsResult {
    /** 握手成功，可以开始收发帧。 */
    class Ok(val session: SourceSyncTlsSession) : SourceSyncTlsResult

    /** 握手失败；[reason] 是 [SourceSyncTlsReasons] 之一。 */
    class Failed(val reason: String, val detail: String? = null) : SourceSyncTlsResult
}

/** 接收端对首个字节的判定：明文（带回放流）或拒绝（对端在说 TLS，而本端是明文端点）。 */
sealed interface SourceSyncTlsProbe {
    /** 是明文：读出的前缀原样回放，调用方继续按帧读。 */
    class Plaintext(val stream: InputStream) : SourceSyncTlsProbe

    /** 拒绝：[reason] 是稳定码。 */
    class Refused(val reason: String, val detail: String? = null) : SourceSyncTlsProbe
}

/**
 * 把嗅探时读掉的前缀原样吐回去的只读流。
 *
 * 与 Windows SourceSyncTlsPrefixedStream 同语义：只读、不可定位、close 不关内层流
 * （内层流的生命周期归连接持有者）。
 */
class SourceSyncTlsPrefixedInputStream internal constructor(
    private val prefix: ByteArray,
    private val inner: InputStream,
) : InputStream() {

    private var offset: Int = 0

    /** 还没吐出去的前缀字节数。 */
    val remainingPrefixBytes: Int get() = prefix.size - offset

    override fun read(): Int {
        if (offset < prefix.size) {
            val value = prefix[offset].toInt() and 0xFF
            offset += 1
            return value
        }
        return inner.read()
    }

    override fun read(buffer: ByteArray, start: Int, length: Int): Int {
        if (length == 0) return 0
        if (offset < prefix.size) {
            val count = minOf(length, prefix.size - offset)
            System.arraycopy(prefix, offset, buffer, start, count)
            offset += count
            return count
        }
        return inner.read(buffer, start, length)
    }

    override fun available(): Int = (prefix.size - offset) + inner.available()

    override fun close() {
        // 刻意不关 inner：前缀流只是视图，连接由调用方关。
    }

    override fun toString(): String = "SourceSyncTlsPrefixedInputStream(" + prefix.size + " bytes)"
}

/**
 * 服务端身份：一枚证书 + 私钥，以及它的 SHA-256(SPKI) 指纹。
 *
 * 与 Windows 的差异（刻意，见 docs/android-source-sync-transfer.md 第 17 节）：
 * Windows 用 PairingCertificate.CreateEphemeral 现场造 RSA-2048 临时证书；
 * Android 侧不自造证书（造证书要 BouncyCastle，不为一个可选能力引依赖），
 * 身份由调用方以 [java.security.KeyStore]（例如随包分发的 PKCS#12）提供。
 * 造不出来就是 [NONE]，握手时如实报 tls-certificate-unavailable，不降级成明文。
 */
class SourceSyncTlsIdentity internal constructor(
    internal val context: SSLContext?,
    val certificateFingerprint: String?,
) {

    /** 是否既有上下文又有指纹（缺一不可）。 */
    val isUsable: Boolean
        get() = context != null && certificateFingerprint != null

    override fun toString(): String =
        "SourceSyncTlsIdentity(" + (certificateFingerprint ?: "<none>") + ")"

    companion object {
        /** 没有身份：握手会报 tls-certificate-unavailable。 */
        val NONE: SourceSyncTlsIdentity = SourceSyncTlsIdentity(null, null)

        /**
         * 从 KeyStore 取身份；任何一步失败都返回 [NONE]，不抛。
         *
         * @param keyStore 含有服务端证书与私钥的库（PKCS#12 / JKS 均可）。
         * @param password 私钥口令。
         */
        fun fromKeyStore(keyStore: KeyStore, password: CharArray): SourceSyncTlsIdentity {
            return try {
                val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                factory.init(keyStore, password)
                val context = SSLContext.getInstance(SourceSyncTls.REQUIRED_PROTOCOL)
                context.init(factory.keyManagers, null, null)
                SourceSyncTlsIdentity(context, firstFingerprint(keyStore))
            } catch (error: GeneralSecurityException) {
                NONE
            } catch (error: IOException) {
                NONE
            }
        }

        private fun firstFingerprint(keyStore: KeyStore): String? {
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (!keyStore.isKeyEntry(alias)) continue
                val certificate = keyStore.getCertificate(alias) as? X509Certificate ?: continue
                val fingerprint = SourceSyncTls.spkiFingerprintOrNull(certificate) ?: continue
                return fingerprint.hex
            }
            return null
        }
    }
}

/**
 * TLS 1.3 记录层 + 公钥指纹钉扎（Windows 权威实现：MediaHub.Sync/SourceSyncTlsTransport.cs）。
 *
 * 四条不变量：
 * - 只协商 TLS 1.3：握手前 enabledProtocols 只留 TLSv1.3，握手后再复核 session.protocol；
 * - 指纹覆盖 SubjectPublicKeyInfo，用的必须是 certificate.publicKey.encoded，
 *   绝不用 certificate.encoded（后者随有效期/序列号变，换签同一把钥匙不该让钉扎失效）；
 * - 比对是常量时间，不符就是硬失败：不信任首次、不降级明文、不重试；
 * - 只用于回环连接（与传输层同一条纪律）。
 *
 * 帧格式完全不变：TLS 只包住记录层，4 字节大端长度帧照旧。
 */
object SourceSyncTls {

    /** 唯一允许的协议版本。 */
    const val REQUIRED_PROTOCOL: String = "TLSv1.3"

    /** 握手时用的 SNI / 目标主机名（与 Windows SourceSyncTlsHandshake.TargetHostName 一致）。 */
    const val TARGET_HOST_NAME: String = "mediahub-sync"

    /** 接收端嗅探的字节数（TLS 记录头是 5 字节）。 */
    const val PROBE_BYTES: Int = 5

    /** 默认握手预算，毫秒；与业务读取超时分开（Windows 侧 15 秒是业务预算）。 */
    const val DEFAULT_HANDSHAKE_TIMEOUT_MS: Int = 10_000

    /** 关闭连接的预算，毫秒（Windows ShutdownBudgetMilliseconds = 2000）。 */
    const val DEFAULT_CLOSE_BUDGET_MS: Int = 2_000

    /** 关连接前排水的最长时间，毫秒（Windows DrainBudgetMilliseconds = 250）。 */
    const val DRAIN_BUDGET_MS: Int = 250

    private const val DRAIN_BUFFER_BYTES = 256

    private const val NO_PIN_DETAIL =
        "TLS 已开启，但没有给出可用的指纹：指纹是 64 位 hex（SHA-256 摘要），" +
            "没有它就没有可比对的对象，连接没有发起。"

    private const val MISMATCH_DETAIL =
        "对端证书的公钥指纹与钉住的不符，连接在一个信封字节读写之前就被关掉了。"

    private const val MISSING_CERTIFICATE_DETAIL =
        "TLS 已开启，但本端没有服务端证书，什么也没有协商、一个字节也没有读。"

    private val clientContext: SSLContext? by lazy { buildClientContext() }

    /**
     * 是不是 TLS 握手记录的开头：0x16（handshake）+ 0x03（major=3）+ minor <= 4。
     *
     * 与 Windows SourceSyncTlsProbe.LooksLikeTlsHandshake 同判据；
     * 明文帧的第一个字节是 4 字节大端长度的高位，正常帧里恒为 0x00，不会误判。
     */
    fun looksLikeTlsHandshake(probe: ByteArray): Boolean {
        if (probe.size < 3) return false
        return (probe[0].toInt() and 0xFF) == 0x16 &&
            (probe[1].toInt() and 0xFF) == 0x03 &&
            (probe[2].toInt() and 0xFF) <= 0x04
    }

    /** 证书公钥指纹（SHA-256(SubjectPublicKeyInfo)，小写 64 hex）；算不出来返回 null。 */
    fun spkiFingerprintOrNull(certificate: X509Certificate?): PairingFingerprint? {
        if (certificate == null) return null
        val encoded = try {
            certificate.publicKey?.encoded
        } catch (error: GeneralSecurityException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        }
        if (encoded == null || encoded.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return PairingFingerprint.fromBytes(digest)
    }

    /** 证书公钥指纹的小写 hex；算不出来返回 null。 */
    fun spkiFingerprint(certificate: X509Certificate?): String? = spkiFingerprintOrNull(certificate)?.hex

    /**
     * 客户端握手：把已连上的明文 socket 升成 TLS 1.3，并把对端钉在 [SourceSyncTlsOptions.Pinned] 的指纹上。
     *
     * 拨号前就把「没有可用指纹」判掉（与 Windows AuthenticateClientAsync 同序），
     * 所以 certificate-unavailable 一定是「一个字节都没发」。
     */
    fun handshakeClient(
        socket: Socket,
        options: SourceSyncTlsOptions,
        timeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    ): SourceSyncTlsResult {
        if (timeoutMs <= 0) {
            return failed(SourceSyncTlsReasons.HANDSHAKE_FAILED, "握手超时必须为正，实际 " + timeoutMs)
        }
        if (!options.enabled) {
            return failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "TLS 没有开启，不该走到握手这一步（明文路径请直接用 socket 的流）。",
            )
        }
        val pinned = options.expectedFingerprint?.let { PairingFingerprint.tryParse(it) }
            ?: return failed(SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE, NO_PIN_DETAIL)
        val context = clientContext
            ?: return failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "本机没有可用的 TLS 1.3 实现，握手没有发起。",
            )
        val tlsSocket = try {
            context.socketFactory.createSocket(socket, TARGET_HOST_NAME, socket.port, true) as SSLSocket
        } catch (error: IOException) {
            return failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "无法在这条连接上启动 TLS：" + error.javaClass.simpleName,
            )
        }
        return try {
            tlsSocket.enabledProtocols = arrayOf(REQUIRED_PROTOCOL)
            tlsSocket.soTimeout = timeoutMs
            tlsSocket.startHandshake()
            verifyClientPeer(tlsSocket, pinned)
        } catch (timeout: SocketTimeoutException) {
            failed(SourceSyncTlsReasons.HANDSHAKE_FAILED, timeoutDetail(timeoutMs))
        } catch (error: SSLException) {
            failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "TLS 握手没有谈成：" + error.javaClass.simpleName,
            )
        } catch (error: IOException) {
            failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "TLS 握手期间连接断了：" + error.javaClass.simpleName,
            )
        } catch (error: IllegalArgumentException) {
            failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "本机 TLS 栈不接受只开 TLS 1.3：" + error.javaClass.simpleName,
            )
        }
    }

    /**
     * 服务端握手：接受一条已经 accept 的明文 socket，升成 TLS 1.3。
     *
     * 判定次序与 Windows AuthenticateServerAsync 一致：先看本端有没有证书，
     * 再看本端证书是不是它对外宣告的那一枚，最后才谈。
     */
    fun handshakeServer(
        socket: Socket,
        identity: SourceSyncTlsIdentity,
        advertisedFingerprint: String? = null,
        timeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    ): SourceSyncTlsResult {
        if (timeoutMs <= 0) {
            return failed(SourceSyncTlsReasons.HANDSHAKE_FAILED, "握手超时必须为正，实际 " + timeoutMs)
        }
        val context = identity.context
        val own = identity.certificateFingerprint?.let { PairingFingerprint.tryParse(it) }
        if (context == null || own == null) {
            return failed(SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE, MISSING_CERTIFICATE_DETAIL)
        }
        val advertised = PairingFingerprint.tryParse(advertisedFingerprint)
        if (advertised != null && !own.matches(advertised)) {
            return failed(
                SourceSyncTlsReasons.FINGERPRINT_MISMATCH,
                "本端持有的证书不是它对外宣告的那一枚，握手没有提供，一个字节也没有读。",
            )
        }
        val tlsSocket = try {
            context.socketFactory.createSocket(socket, peerHostOf(socket), socket.port, true) as SSLSocket
        } catch (error: IOException) {
            return failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "无法在这条连接上接受 TLS：" + error.javaClass.simpleName,
            )
        }
        return try {
            tlsSocket.useClientMode = false
            tlsSocket.enabledProtocols = arrayOf(REQUIRED_PROTOCOL)
            tlsSocket.soTimeout = timeoutMs
            tlsSocket.startHandshake()
            val protocol = tlsSocket.session?.protocol
            if (protocol != REQUIRED_PROTOCOL) {
                return failed(
                    SourceSyncTlsReasons.HANDSHAKE_FAILED,
                    "握手用的是 " + protocol + "，只接受 " + REQUIRED_PROTOCOL + "。",
                )
            }
            SourceSyncTlsResult.Ok(
                SourceSyncTlsSession(tlsSocket, tlsSocket.inputStream, tlsSocket.outputStream, own.hex),
            )
        } catch (timeout: SocketTimeoutException) {
            failed(SourceSyncTlsReasons.HANDSHAKE_FAILED, timeoutDetail(timeoutMs))
        } catch (error: SSLException) {
            failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "TLS 握手没有谈成：" + error.javaClass.simpleName,
            )
        } catch (error: IOException) {
            failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "TLS 握手期间连接断了：" + error.javaClass.simpleName,
            )
        } catch (error: IllegalArgumentException) {
            failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "本机 TLS 栈不接受只开 TLS 1.3：" + error.javaClass.simpleName,
            )
        }
    }

    /**
     * 接收端嗅探：读最多 5 字节，判断对端在说明文帧还是 TLS 记录。
     *
     * 读掉的前缀会原样回放给调用方，所以明文路径与「不嗅探」逐字节等价。
     * 只有在**接收端**做嗅探：发送端从不读入站数据（Windows §7）。
     */
    fun probePlaintext(input: InputStream): SourceSyncTlsProbe {
        val prefix = ByteArray(PROBE_BYTES)
        var filled = 0
        while (filled < PROBE_BYTES) {
            val read = try {
                input.read(prefix, filled, PROBE_BYTES - filled)
            } catch (error: IOException) {
                -1
            }
            if (read <= 0) break
            filled += read
        }
        val probe = if (filled == prefix.size) prefix else prefix.copyOf(filled)
        if (looksLikeTlsHandshake(probe)) {
            return SourceSyncTlsProbe.Refused(
                SourceSyncTlsReasons.NOT_ENABLED,
                "对端用 TLS 握手记录开这条连接，但本端的传输端点是明文的；" +
                    "要么在对端开 TLS，要么让对端发明文帧。",
            )
        }
        return SourceSyncTlsProbe.Plaintext(SourceSyncTlsPrefixedInputStream(probe, input))
    }

    /**
     * TLS 路径的 graceful 关闭：先半关写端，再排水到 EOF，最后关 socket。
     *
     * 为什么不能直接 close：TLS 1.3 服务端握手后立刻发 NewSessionTicket，
     * 带着未读的入站记录直接 Dispose 会变成 abortive close（RST），对端读到的是 io-failed。
     * 这里的顺序与 Windows CloseQuietlyAsync 一致（2 秒预算 + 250 毫秒排水）。
     */
    fun gracefulClose(socket: Socket?, budgetMs: Int = DEFAULT_CLOSE_BUDGET_MS) {
        if (socket == null) return
        try {
            try {
                socket.shutdownOutput()
            } catch (error: Exception) {
                // 对端已经关了写端，或者本机（部分 Android 版本）不支持半关：继续排水即可。
            }
            drainQuietly(socket, budgetMs)
        } finally {
            LoopbackOnly.closeQuietly(socket)
        }
    }

    private fun drainQuietly(socket: Socket, budgetMs: Int) {
        val input = try {
            socket.getInputStream()
        } catch (error: IOException) {
            return
        }
        try {
            socket.soTimeout = minOf(budgetMs, DRAIN_BUDGET_MS)
        } catch (error: Exception) {
            // 已关的 socket 设不了超时，下面的读会立刻抛，交给 catch。
        }
        val buffer = ByteArray(DRAIN_BUFFER_BYTES)
        val deadline = System.currentTimeMillis() + budgetMs
        try {
            while (System.currentTimeMillis() < deadline) {
                if (input.read(buffer) < 0) return
            }
        } catch (error: IOException) {
            // 关闭途中的读失败不影响「已经关了」这个事实。
        }
    }

    private fun verifyClientPeer(tlsSocket: SSLSocket, pinned: PairingFingerprint): SourceSyncTlsResult {
        val session = tlsSocket.session
            ?: return failed(SourceSyncTlsReasons.HANDSHAKE_FAILED, "握手完成但拿不到会话，判定不了协议版本。")
        if (session.protocol != REQUIRED_PROTOCOL) {
            return failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "握手用的是 " + session.protocol + "，只接受 " + REQUIRED_PROTOCOL + "。",
            )
        }
        val certificates = try {
            session.peerCertificates
        } catch (error: SSLPeerUnverifiedException) {
            null
        }
        val certificate = certificates?.firstOrNull() as? X509Certificate
            ?: return failed(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                "对端完成了握手但没有出示证书，没有可比对的对象。",
            )
        val seen = spkiFingerprintOrNull(certificate)
            ?: return failed(
                SourceSyncTlsReasons.CERTIFICATE_UNAVAILABLE,
                "对端证书没有可导出的公钥，指纹算不出来。",
            )
        if (!seen.matches(pinned)) {
            return failed(SourceSyncTlsReasons.FINGERPRINT_MISMATCH, MISMATCH_DETAIL)
        }
        return SourceSyncTlsResult.Ok(
            SourceSyncTlsSession(tlsSocket, tlsSocket.inputStream, tlsSocket.outputStream, seen.hex),
        )
    }

    private fun buildClientContext(): SSLContext? {
        val trust = arrayOf<TrustManager>(AcceptAnyCertificate)
        return try {
            val context = SSLContext.getInstance(REQUIRED_PROTOCOL)
            context.init(null, trust, null)
            context
        } catch (error: GeneralSecurityException) {
            try {
                val fallback = SSLContext.getInstance("TLS")
                fallback.init(null, trust, null)
                fallback
            } catch (second: GeneralSecurityException) {
                null
            }
        }
    }

    private fun peerHostOf(socket: Socket): String =
        socket.inetAddress?.hostAddress ?: TARGET_HOST_NAME

    private fun timeoutDetail(timeoutMs: Int): String =
        "TLS 握手在 " + timeoutMs + " 毫秒内没有完成，对端不是可以钉扎的 TLS 1.3 端点。"

    private fun failed(reason: String, detail: String?): SourceSyncTlsResult =
        SourceSyncTlsResult.Failed(reason, detail)

    /**
     * 一律接受的信任管理器：链验证在这里没有意义（自签证书没有 CA），
     * 真正的判定是钉扎那一步的常量时间比对。仅用于客户端握手。
     */
    private object AcceptAnyCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
