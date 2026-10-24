package com.mediahub.core.common.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 传输层可选对端认证（W6-25，对齐 Windows W6-24）的五个稳定码。
 *
 * 这五个码刻意**不并进** SourceSyncTransferReasons：那十码是封闭契约（两端各有断言钉住数量与顺序），
 * 往里加一条等于悄悄拓宽别人 switch 的集合。这里另立一套，顺序与 Windows SourceSyncTransferAuthReasons.All 逐条相同。
 *
 * 五个码都不含口令、nonce 或信封内容：它们只说「哪一种证明没过」。
 */
object SourceSyncTransferAuthReasons {
    /** 请求了认证，但没有可绑定 tag 的挑战（nonce），一个字节都没写。 */
    const val AUTH_REQUIRED: String = "auth-required"

    /** 信封收满了，但对端始终没证明它持有口令（没送 tag，或送 tag 却没宣布挑战）。 */
    const val AUTH_MISSING: String = "auth-missing"

    /** 对端的 tag 与「本口令 + 本 nonce + 本信封」不符；不记 nonce。 */
    const val AUTH_MISMATCH: String = "auth-mismatch"

    /** 本进程已经在这个 nonce 下接受过一封信封，本次是重放。 */
    const val AUTH_REPLAYED: String = "auth-replayed"

    /** 认证开着，但本进程手里没有口令可用于计算或校验 tag。 */
    const val AUTH_SECRET_UNAVAILABLE: String = "auth-secret-unavailable"

    /** 全部码，顺序与 Windows 的 All 一致（顺序本身被测试钉住）。 */
    val ALL: List<String> = listOf(
        AUTH_REQUIRED,
        AUTH_MISSING,
        AUTH_MISMATCH,
        AUTH_REPLAYED,
        AUTH_SECRET_UNAVAILABLE,
    )
}

/**
 * 证明回环传输的对端持有共享口令（W6-25，Windows 权威实现：MediaHub.Sync/SourceSyncTransferAuthenticator.cs）。
 *
 * 传输层是明文回环 TCP：挡得住局域网，挡不住同机其它进程。本层用最小的原语补上这一格——
 * HMAC-SHA256(nonce 的 UTF-8 字节 ‖ 信封字节)，nonce 就是邀请里那段挑战。构造刻意是「给你实际读到的字节打 tag」：
 * tag 同时覆盖 nonce 与重组后的信封，所以对端无法把一枚合法 tag 挪到别的载荷或别的 nonce 上。
 *
 * 这是**认证**不是加密：信封仍旧明文过回环。没有密钥协商、没有轮换、没有跨进程重放保护（见文档未覆盖项）。
 */
object SourceSyncTransferAuthenticator {

    /** 可选 tag 帧的类型字节；必须紧跟最后一块数据之后。 */
    const val TAG_FRAME_KIND: Int = 0x03

    /** HMAC-SHA256 tag 的字节数。 */
    const val TAG_BYTES: Int = 32

    /** tag 帧的总长：类型字节 1 + tag 32。 */
    const val TAG_FRAME_BYTES: Int = 1 + TAG_BYTES

    /** 生成 nonce 用的熵字节数（线上是 base64url）。 */
    const val NONCE_ENTROPY_BYTES: Int = 16

    private const val HMAC_ALGORITHM: String = "HmacSHA256"

    /**
     * 算 HMAC-SHA256(secret, nonce ‖ envelope)，nonce 取文本的 UTF-8 字节。
     *
     * 空口令属于调用方的编程错误，抛 IllegalArgumentException（Windows 抛 ArgumentException）；
     * nonce 为 null 抛 NullPointerException 同 Windows 的 ArgumentNullException。
     */
    fun createTag(secret: ByteArray, nonce: String, envelope: ByteArray): ByteArray =
        createTag(secret, nonce.toByteArray(Charsets.UTF_8), envelope)

    /** 同上，但 nonce 用线上原样的字节。 */
    fun createTag(secret: ByteArray, nonce: ByteArray, envelope: ByteArray): ByteArray {
        require(secret.isNotEmpty()) { "A tag needs a non-empty secret." }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
        mac.update(nonce)
        mac.update(envelope)
        return mac.doFinal()
    }

    /**
     * 常量时间比较 tag（MessageDigest.isEqual，同 Windows CryptographicOperations.FixedTimeEquals）。
     *
     * 长度不对直接返回 false：长度是公开事实（帧里就带着），提前返回不泄漏任何东西，
     * 而且能避免拿两个不等长的数组去做常量时间比较。空口令永远验不过。
     */
    fun verifyTag(secret: ByteArray, nonce: String, envelope: ByteArray, tag: ByteArray): Boolean =
        verifyTag(secret, nonce.toByteArray(Charsets.UTF_8), envelope, tag)

    /** 同上，nonce 用线上原样的字节。 */
    fun verifyTag(secret: ByteArray, nonce: ByteArray, envelope: ByteArray, tag: ByteArray): Boolean {
        if (secret.isEmpty() || tag.size != TAG_BYTES) {
            return false
        }
        val expected = createTag(secret, nonce, envelope)
        return MessageDigest.isEqual(expected, tag)
    }

    /**
     * 造一枚 tag 帧：[0x03][32 字节 tag]。
     *
     * 单独抽出来是因为发送端与测试都要拼这一帧；帧长的唯一真理在 TAG_FRAME_BYTES。
     */
    fun createTagFrame(secret: ByteArray, nonce: ByteArray, envelope: ByteArray): ByteArray {
        val tag = createTag(secret, nonce, envelope)
        val frame = ByteArray(TAG_FRAME_BYTES)
        frame[0] = TAG_FRAME_KIND.toByte()
        tag.copyInto(frame, 1)
        return frame
    }

    /**
     * 造一枚新 nonce：16 随机字节的 base64url（无填充，22 字符），与 Windows CreateNonce 同款。
     *
     * 用 base64url 而不是 base64 是为了让它在挑战帧里经过 UTF-8 trim 之后逐字不变。
     */
    fun createNonce(): String {
        val entropy = ByteArray(NONCE_ENTROPY_BYTES)
        SecureRandom().nextBytes(entropy)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
    }
}

/**
 * 记住本进程已经接受过的 nonce，让同一个 (deviceId, nonce) 不能被重放（W6-25，对齐 Windows SourceSyncNonceCache）。
 *
 * 窗口刻意小且有界：默认 64 条、先进先出淘汰最旧；**不落盘**，进程重启即清空（这是已文档化的跨进程缺口）。
 * 条目按 deviceId 分区，两台设备碰巧选了同一个 nonce 不会互相顶掉。
 *
 * 线程安全：一个监听端可能同时服务多个连接，所以 accept 走 synchronized。
 */
class SourceSyncNonceCache(capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 1) { "The cache needs room for at least one nonce." }
    }

    /** 淘汰前最多记多少条。 */
    val capacity: Int = capacity

    private val gate = Any()
    private val order = ArrayDeque<String>()
    private val seen = HashSet<String>()

    /** 现在记着多少条。 */
    val count: Int
        get() = synchronized(gate) { seen.size }

    /**
     * 记一个 nonce；第一次见到返回 true，重复返回 false。
     *
     * 空 nonce 是调用方的编程错误（空挑战在读帧阶段就被判 frame-invalid），抛 IllegalArgumentException。
     */
    fun accept(deviceId: String?, nonce: String): Boolean {
        require(nonce.isNotEmpty()) { "A nonce is never empty; an empty challenge is rejected before this point." }
        val key = (deviceId ?: "") + KEY_SEPARATOR + nonce
        synchronized(gate) {
            if (!seen.add(key)) {
                return false
            }
            order.addLast(key)
            while (order.size > capacity) {
                seen.remove(order.removeFirst())
            }
            return true
        }
    }

    /** 忘掉全部 nonce；下一次任何配对都算首次。 */
    fun clear() {
        synchronized(gate) {
            seen.clear()
            order.clear()
        }
    }

    companion object {
        /** 默认容量，与 Windows DefaultCapacity 一致。 */
        const val DEFAULT_CAPACITY: Int = 64

        /** 分区键的分隔符：deviceId 与 nonce 之间用 U+001F（单元分隔符），避免拼接歧义。 */
        private const val KEY_SEPARATOR: String = "\u001f"
    }
}

/**
 * 一次传输某一端的可选认证设置（W6-25，对齐 Windows SourceSyncTransferOptions）。
 *
 * 刻意是普通 class 而**不是** data class：data class 自动生成的 toString 会把 secret 打出来，
 * 而这些 options 会进诊断文本与异常消息。下面的 toString 显式打码，只说明「有没有口令、多少字节」。
 *
 * OFF（以及传 null）必须让线上字节与 W6-22 逐字节相同：没有 tag 帧、没有额外 flush、帧数不变。
 */
class SourceSyncTransferOptions private constructor(
    /** 是否为本端开启 tag 帧；默认关闭。 */
    val authenticate: Boolean,
    secret: ByteArray,
    /** 复放窗口；为 null 时本对象自己持有一个私有窗口。 */
    val nonces: SourceSyncNonceCache?,
    /** 复放记忆的分区用设备 id，只有接收端用，永不发送。 */
    val deviceId: String?,
) {

    private val secretBytes: ByteArray = secret.copyOf()

    /** 口令副本，供传输层算 tag 用（不额外复制）。 */
    internal val secretForAuthentication: ByteArray
        get() = secretBytes

    /** 口令副本（外部拿到的都是拷贝，改它不会影响本对象）。 */
    val secret: ByteArray
        get() = secretBytes.copyOf()

    /** 只有「开着认证」且「手里真有口令」时才为真。 */
    val hasSecret: Boolean
        get() = authenticate && secretBytes.isNotEmpty()

    private val fallbackNonces: Lazy<SourceSyncNonceCache> = lazy { SourceSyncNonceCache() }

    /** 本次要用的复放窗口：给了就用给的，没给就用自己的私有窗口。 */
    val activeNonces: SourceSyncNonceCache
        get() = nonces ?: fallbackNonces.value

    /** 打码格式化：口令永不出现，只报字节数。 */
    override fun toString(): String =
        "SourceSyncTransferOptions { Authenticate = " + (if (authenticate) "true" else "false") +
            ", Secret = " + (if (secretBytes.isEmpty()) "<none>" else "<redacted " + secretBytes.size + " bytes>") +
            ", DeviceId = " + (if (deviceId.isNullOrEmpty()) "<none>" else "'" + deviceId + "'") +
            " }"

    companion object {
        /** 默认：认证关闭，必须产出与 W6-22 完全相同的字节流。 */
        val OFF: SourceSyncTransferOptions = SourceSyncTransferOptions(false, ByteArray(0), null, null)

        /** 开启认证并复制一份口令；secret 为 null 或空时构造出的 options 会报 auth-secret-unavailable 而不是认证。 */
        fun withSecret(
            secret: ByteArray?,
            nonces: SourceSyncNonceCache? = null,
            deviceId: String? = null,
        ): SourceSyncTransferOptions = SourceSyncTransferOptions(
            authenticate = true,
            secret = secret?.copyOf() ?: ByteArray(0),
            nonces = nonces,
            deviceId = deviceId,
        )
    }
}
