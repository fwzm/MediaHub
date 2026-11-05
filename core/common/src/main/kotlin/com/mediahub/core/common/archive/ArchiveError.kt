package com.mediahub.core.common.archive

/**
 * 离线加密归档（ADR-S005）的稳定错误码。
 *
 * 十二个码逐字抄自 Windows `MediaHub.Sync.ArchiveError`：两端对同一个坏文件必须给出同一个码，
 * 排障时不做映射。带参数的两种码用前缀 + `:` + 值表示：
 * `unsupported-version:<版本号>`、`unknown-kdf:<kdf 名>`。
 *
 * 错口令与文件被篡改**故意共用一个码**：GCM 先验证 tag 再解密，两者在密码学上不可区分；
 * 分开报告等于给攻击者一个口令校验 oracle。
 */
object ArchiveError {

    /** 前 8 字节不是 `MHSYNCA1`（空输入也算）——先于任何解析发生。 */
    const val MAGIC_MISMATCH: String = "magic-mismatch"

    /** 头部长度字段、头部 JSON 本身或 salt/nonce 的 base64 解不出来。 */
    const val CORRUPT_HEADER: String = "corrupt-header"

    /** 前缀：`unsupported-version:<版本号>`。 */
    const val UNSUPPORTED_VERSION_PREFIX: String = "unsupported-version"

    /** 前缀：`unknown-kdf:<名称>`。 */
    const val UNKNOWN_KDF_PREFIX: String = "unknown-kdf"

    /** 迭代数低于安全下限（[ArchiveKdf.MIN_ITERATIONS]）。 */
    const val INVALID_ITERATIONS: String = "invalid-iterations"

    /** 口令为空或只有空白。 */
    const val PASSPHRASE_EMPTY: String = "passphrase-empty"

    /** 口令太短、命中弱口令表、单字符重复或全是数字。 */
    const val PASSPHRASE_TOO_WEAK: String = "passphrase-too-weak"

    /** 归档声称携带凭据；写侧与读侧都必须拒绝（拒绝而不是静默剥掉）。 */
    const val SECRETS_NOT_ALLOWED: String = "secrets-not-allowed"

    /** 密文或明文超过 [ArchiveHeader.MAX_PAYLOAD_BYTES]。 */
    const val PAYLOAD_TOO_LARGE: String = "payload-too-large"

    /** 扣掉头部与 tag 之后没有密文，或密文长度不合理。 */
    const val CORRUPT_CIPHERTEXT: String = "corrupt-ciphertext"

    /** 口令错或是文件被改过（GCM tag 校验失败）。 */
    const val WRONG_PASSPHRASE_OR_TAMPERED: String = "wrong-passphrase-or-tampered"

    /** 解密成功，但明文不是 UTF-8、不是 JSON 或不是合法的同步信封。 */
    const val MALFORMED_PAYLOAD: String = "malformed-payload"

    /** 十二个基础码（两个带参数的只列前缀），顺序与 Windows 常量声明顺序一致。 */
    val ALL: List<String> = listOf(
        MAGIC_MISMATCH,
        CORRUPT_HEADER,
        UNSUPPORTED_VERSION_PREFIX,
        UNKNOWN_KDF_PREFIX,
        INVALID_ITERATIONS,
        PASSPHRASE_EMPTY,
        PASSPHRASE_TOO_WEAK,
        SECRETS_NOT_ALLOWED,
        PAYLOAD_TOO_LARGE,
        CORRUPT_CIPHERTEXT,
        WRONG_PASSPHRASE_OR_TAMPERED,
        MALFORMED_PAYLOAD,
    )

    fun unsupportedVersion(version: Int): String = UNSUPPORTED_VERSION_PREFIX + ":" + version

    fun unknownKdf(kdf: String?): String = UNKNOWN_KDF_PREFIX + ":" + (kdf ?: "")
}

/**
 * 归档读写的失败。reason 是上面的稳定机器码；message 面向日志，**绝不包含明文、口令或载荷字节**。
 *
 * 与 Windows `ArchiveException` 一一对应：同一个坏文件在两端抛出的 reason 逐字相同。
 */
class ArchiveException(
    val reason: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
