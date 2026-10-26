package com.mediahub.core.common.archive

import com.mediahub.core.common.sync.WireText

/**
 * 归档口令策略，逐字对齐 Windows `MediaHub.Sync.ArchivePassphrase`。
 *
 * 为什么加密之外还要管口令：GCM 保证「密文没被改」，但救不了「用户用了 123456」。
 * 弱口令在离线段是唯一的访问控制，所以写侧与读侧都先过这一层。
 *
 * 判定次序（次序就是外部可观察优先级，与 Windows 相同）：
 * 空白 → passphrase-empty；原始长度不足 12 → passphrase-too-weak；
 * 含孤立代理项 → passphrase-too-weak；trim 后命中弱口令表 → passphrase-too-weak；
 * 单字符重复或全 ASCII 数字 → passphrase-too-weak。
 *
 * 「空白」「trim」「不区分大小写」全部走 [WireText] 的显式规则，不用 Kotlin 自带的
 * isBlank()/trim()/equals(ignoreCase = true)：后三者的空白集与大小写折叠跟着 JVM 的 Unicode 版本走，
 * 与 .NET 不同，同一口令会在两端得到不同的稳定码（详见 WireText 的 KDoc）。
 *
 * 为什么孤立代理项也要在策略层拒绝：两端口令校验都放行后，PBKDF2 的编码就分叉了——
 * .NET 抛 EncoderFallbackException（不是 ArchiveException，会穿透调用方），JVM 静默替换成 ?
，
 * 写出的归档对端永远读不开。在策略层判掉，两端都只报 passphrase-too-weak，不新增码。
 */
object ArchivePassphrase {

    /** 最小长度（按**未 trim** 的原始长度算，与 Windows 的 passphrase.Length 一致）。 */
    const val MIN_LENGTH: Int = 12

    /**
     * 弱口令表，24 条、顺序照抄 Windows。这里的值是「已经泄露到字典里的形状」，
     * 不是安全建议——新增条目会让两端对同一口令给出不同的判定，必须同时改两端。
     */
    private val WEAK_PASSPHRASES: List<String> = listOf(
        "password",
        "password1",
        "password123",
        "password1234",
        "passw0rd",
        "123456",
        "1234567",
        "12345678",
        "123456789",
        "1234567890",
        "12345678901",
        "123456789012",
        "qwertyuiop",
        "qwerty123456",
        "qwertyuiop123",
        "1q2w3e4r5t6y",
        "letmein12345",
        "iloveyou1234",
        "administrator",
        "trustno1trustno1",
        "aaaaaaaaaaaa",
        "abcabcabcabc",
        "changeme1234",
        "welcome12345",
    )

    /** 校验口令：合法返回 null，否则返回稳定码。 */
    fun tryValidate(passphrase: String?): String? {
        if (WireText.isBlank(passphrase)) return ArchiveError.PASSPHRASE_EMPTY
        val text = passphrase!!
        if (text.length < MIN_LENGTH) return ArchiveError.PASSPHRASE_TOO_WEAK
        if (WireText.hasLoneSurrogate(text)) return ArchiveError.PASSPHRASE_TOO_WEAK
        val trimmed = WireText.trim(text)
        if (WEAK_PASSPHRASES.any { WireText.equalsAsciiIgnoreCase(it, trimmed) }) return ArchiveError.PASSPHRASE_TOO_WEAK
        if (isSingleRepeatedCharacter(trimmed) || isAllAsciiDigits(trimmed)) return ArchiveError.PASSPHRASE_TOO_WEAK
        return null
    }

    /** 校验口令：不合法直接抛 [ArchiveException]。错误消息不含口令本身。 */
    fun validate(passphrase: String?) {
        val reason = tryValidate(passphrase) ?: return
        throw ArchiveException(reason, messageOf(reason))
    }

    /** 口令类码的英文消息，与 Windows 的 `ArchivePassphrase.Validate` 文案一致。 */
    fun messageOf(reason: String): String = when (reason) {
        ArchiveError.PASSPHRASE_EMPTY -> "A passphrase is required to write or read an archive."
        else -> "The passphrase must be at least " + MIN_LENGTH +
            " characters and must not be a common one."
    }

    private fun isSingleRepeatedCharacter(value: String): Boolean =
        value.isNotEmpty() && value.all { it == value[0] }

    private fun isAllAsciiDigits(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' }
}
