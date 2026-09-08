package com.mediahub.core.common.backup

import java.security.SecureRandom

/**
 * 备份文件序列化/反序列化管道（纯 JVM，Phase 1I-A）。
 *
 * 导出：payload JSON → PBKDF2 派生密钥 → AES-256-GCM(AAD) 加密 → envelope JSON → bytes
 * 导入：有界读取 bytes → envelope 验证（magic/版本/算法白名单/迭代范围/salt/nonce/密文最短）
 *      → PBKDF2 重派生 → AES-256-GCM(AAD) 解密 → 内层版本/结构统一校验 → payload
 *
 * - 统一格式验证入口 [validateEnvelope] + [validatePayload]；合法 JSON ≠ 合法备份
 * - 错误分类：AuthenticationFailed（密码错误或密文被篡改，无法区分）/
 *   Corrupted（结构/Base64/nonce/salt/引用/计数异常）/ NotABackupFile / VersionTooNew
 * - 解密链路上所有可预期异常（KDF 参数、Base64、nonce 长度）都转为类型化结果，
 *   不允许 `require` 异常逃出 [import]
 * - 文件大小上限 [MAX_FILE_BYTES]（有界读取——SAF 读取阶段即限制，不全量加载任意大文件）；
 *   导出结果同样受此上限约束（新文件必须满足自己的导入限制）
 */
object BackupSerializer {

    const val MAX_FILE_BYTES = 10 * 1024 * 1024 // 10MB
    const val MIN_CIPHERTEXT_BYTES = 16 // GCM tag 至少 16 字节

    /** 内层白名单 section 名（manifest.includedSections / recordCounts 键域）。 */
    val KNOWN_SECTIONS = setOf("servers", "progress", "preferences")

    /** 导出：加密并序列化为 `.mhbackup` 文件字节。 */
    fun export(
        payload: BackupDtos.BackupPayload,
        password: CharArray,
        maxBytes: Int = MAX_FILE_BYTES,
        testIterations: Int? = null,
    ): ByteArray {
        val payloadJson = BackupDtos.encodePayload(payload).toByteArray(Charsets.UTF_8)
        val salt = BackupCrypto.randomSalt()
        val derived = BackupCrypto.deriveKey(
            password, salt,
            testIterations ?: BackupCrypto.PBKDF2_ITERATIONS,
            BackupCrypto.PBKDF2_KEY_BITS,
            testIterations = testIterations,
        )
        val nonce = BackupCrypto.randomNonce()
        val aad = BackupCrypto.aadFor(BackupCrypto.MAGIC, BackupCrypto.FORMAT_VERSION, derived.params.algorithm, derived.params.iterations)
        val ciphertext = BackupCrypto.encrypt(payloadJson, derived.key, nonce, aad)

        val envelope = BackupFileFormat.Envelope(
            magic = BackupCrypto.MAGIC,
            formatVersion = BackupCrypto.FORMAT_VERSION,
            kdfAlgorithm = derived.params.algorithm,
            kdfSaltB64 = BackupFileFormat.encodeB64(derived.params.salt),
            kdfIterations = derived.params.iterations,
            kdfKeyLengthBits = derived.params.keyLengthBits,
            nonceB64 = BackupFileFormat.encodeB64(nonce),
            ciphertextB64 = BackupFileFormat.encodeB64(ciphertext),
        )
        val bytes = BackupFileFormat.encodeEnvelope(envelope).toByteArray(Charsets.UTF_8)
        // 导出文件必须满足自身的导入上限，否则生成一个无法恢复的备份
        check(bytes.size <= maxBytes) { "备份文件超过 ${maxBytes / 1024 / 1024}MB 上限" }
        return bytes
    }

    /** 导入结果。 */
    sealed interface ImportResult {
        data class Ok(val payload: BackupDtos.BackupPayload) : ImportResult
        data class AuthenticationFailed(val cause: String) : ImportResult
        data class Corrupted(val reason: String) : ImportResult
        data class VersionTooNew(val fileVersion: Int, val readerVersion: Int) : ImportResult
        data object NotABackupFile : ImportResult
    }

    /** envelope 结构与参数统一验证（解密前）。 */
    private fun validateEnvelope(env: BackupFileFormat.Envelope): ImportResult? {
        if (env.magic != BackupCrypto.MAGIC) return ImportResult.NotABackupFile
        if (env.formatVersion > BackupCrypto.FORMAT_VERSION) {
            return ImportResult.VersionTooNew(env.formatVersion, BackupCrypto.MINIMUM_READER_VERSION)
        }
        if (env.formatVersion < 1) return ImportResult.Corrupted("formatVersion 非法：${env.formatVersion}")
        if (env.kdfAlgorithm !in BackupCrypto.SUPPORTED_KDF_ALGORITHMS) {
            return ImportResult.Corrupted("不支持的 KDF 算法：${env.kdfAlgorithm}")
        }
        if (env.kdfIterations !in BackupCrypto.MIN_ITERATIONS..BackupCrypto.MAX_ITERATIONS) {
            return ImportResult.Corrupted("KDF 迭代数超出允许范围：${env.kdfIterations}")
        }
        if (env.kdfKeyLengthBits != BackupCrypto.PBKDF2_KEY_BITS) {
            return ImportResult.Corrupted("密钥长度必须 256 位")
        }
        val salt = runCatching { BackupFileFormat.decodeB64(env.kdfSaltB64) }
            .getOrElse { return ImportResult.Corrupted("salt Base64 无效") }
        if (salt.size < BackupCrypto.PBKDF2_SALT_BYTES) {
            return ImportResult.Corrupted("salt 长度不足（${salt.size} < ${BackupCrypto.PBKDF2_SALT_BYTES}）")
        }
        val nonce = runCatching { BackupFileFormat.decodeB64(env.nonceB64) }
            .getOrElse { return ImportResult.Corrupted("nonce Base64 无效") }
        if (nonce.size != BackupCrypto.GCM_NONCE_BYTES) {
            return ImportResult.Corrupted("nonce 长度必须 ${BackupCrypto.GCM_NONCE_BYTES} 字节（实际 ${nonce.size}）")
        }
        val ct = runCatching { BackupFileFormat.decodeB64(env.ciphertextB64) }
            .getOrElse { return ImportResult.Corrupted("密文 Base64 无效") }
        if (ct.size < MIN_CIPHERTEXT_BYTES) {
            return ImportResult.Corrupted("密文过短（不足 GCM 标签长度）")
        }
        return null // 通过
    }

    /**
     * 内层 payload 统一验证（解密后、返回 Ok 前）。
     * 版本一致性、sections/计数、重复 ID、孤立引用以外的引用结构、字段范围、URL 值级守卫。
     * 孤立进度引用（serverBackupId 无对应服务器）不算格式错误——进入预览披露，由用户决定。
     */
    private fun validatePayload(payload: BackupDtos.BackupPayload): ImportResult? {
        val m = payload.manifest
        if (m.formatVersion < 1 || m.formatVersion > BackupCrypto.FORMAT_VERSION) {
            return ImportResult.VersionTooNew(m.formatVersion, BackupCrypto.MINIMUM_READER_VERSION)
        }
        if (m.minimumReaderVersion < 1) return ImportResult.Corrupted("minimumReaderVersion 非法：${m.minimumReaderVersion}")
        // 文件要求比本读端更新的读版本 → 明确报版本过新（引导升级），先于一致性检查
        if (m.minimumReaderVersion > BackupCrypto.MINIMUM_READER_VERSION) {
            return ImportResult.VersionTooNew(m.minimumReaderVersion, BackupCrypto.MINIMUM_READER_VERSION)
        }
        if (m.minimumReaderVersion > m.formatVersion) {
            return ImportResult.Corrupted("minimumReaderVersion(${m.minimumReaderVersion}) 大于 formatVersion(${m.formatVersion})")
        }
        if (m.createdAtEpochMs < 0) return ImportResult.Corrupted("createdAtEpochMs 非法")

        // sections 与计数一致性
        for (section in m.includedSections) {
            if (section !in KNOWN_SECTIONS) return ImportResult.Corrupted("未知 section：$section")
        }
        if (m.includedSections.size != m.includedSections.toSet().size) {
            return ImportResult.Corrupted("includedSections 存在重复项")
        }
        for ((key, count) in m.recordCounts) {
            if (key !in KNOWN_SECTIONS) return ImportResult.Corrupted("recordCounts 未知键：$key")
            if (count < 0) return ImportResult.Corrupted("recordCounts[$key] 为负数")
        }
        val actualCounts = mapOf(
            "servers" to payload.servers.size,
            "progress" to payload.progress.size,
            "preferences" to if (payload.preferences != null) 1 else 0,
        )
        for ((key, declared) in m.recordCounts) {
            if (actualCounts[key] != declared) {
                return ImportResult.Corrupted("recordCounts[$key]=$declared 与实际 ${actualCounts[key]} 不符")
            }
        }

        // 服务器：重复 ID、字段范围、URL 值级守卫
        val serverIds = HashSet<String>(payload.servers.size)
        for (dto in payload.servers) {
            if (dto.backupId.isBlank()) return ImportResult.Corrupted("服务器 backupId 为空")
            if (!serverIds.add(dto.backupId)) return ImportResult.Corrupted("重复的服务器 ID：${dto.backupId}")
            if (dto.name.isBlank()) return ImportResult.Corrupted("服务器 ${dto.backupId} 名称为空")
            if (dto.endpoints.isEmpty()) return ImportResult.Corrupted("服务器 ${dto.backupId} 无任何线路")
            for (ep in dto.endpoints) {
                if (ep.url.isBlank()) return ImportResult.Corrupted("服务器 ${dto.backupId} 存在空 URL 线路")
                BackupUrlGuard.inspect(ep.url)?.let { v ->
                    return ImportResult.Corrupted("服务器 ${dto.backupId} 线路 URL 携带疑似凭据（${describe(v)}），拒绝导入")
                }
            }
        }

        // 进度：重复键、字段范围
        val progressKeys = HashSet<String>(payload.progress.size)
        for (dto in payload.progress) {
            val key = "${dto.serverBackupId}/${dto.itemId}"
            if (!progressKeys.add(key)) return ImportResult.Corrupted("重复的播放记录：$key")
            if (dto.serverBackupId.isBlank() || dto.itemId.isBlank()) return ImportResult.Corrupted("播放记录存在空 ID")
            if (dto.positionMs < 0 || dto.durationMs < 0) return ImportResult.Corrupted("播放记录 $key 时长字段为负")
            if (dto.updatedAtEpochMs < 0) return ImportResult.Corrupted("播放记录 $key 时间戳为负")
        }
        return null
    }

    private fun describe(v: BackupUrlGuard.Violation): String = when (v) {
        is BackupUrlGuard.Violation.UserInfo -> "URL user-info 段"
        is BackupUrlGuard.Violation.SensitiveQuery -> "query 参数 ${v.key}"
    }

    /** 导入：有界读取 → 统一验证 → AAD 解密 → 内层统一校验。 */
    fun import(data: ByteArray, password: CharArray, testIterations: Int? = null): ImportResult {
        if (data.size > MAX_FILE_BYTES) return ImportResult.Corrupted("文件超过 ${MAX_FILE_BYTES / 1024 / 1024}MB 上限")
        val text = String(data, Charsets.UTF_8)
        if (!text.contains(BackupCrypto.MAGIC)) return ImportResult.NotABackupFile

        val envelope = try {
            BackupFileFormat.decodeEnvelope(text)
        } catch (e: Exception) {
            return ImportResult.Corrupted("备份文件格式无法解析")
        }

        validateEnvelope(envelope)?.let { return it }

        val salt = BackupFileFormat.decodeB64(envelope.kdfSaltB64)
        val nonce = BackupFileFormat.decodeB64(envelope.nonceB64)
        val ciphertext = BackupFileFormat.decodeB64(envelope.ciphertextB64)

        val aad = BackupCrypto.aadFor(envelope.magic, envelope.formatVersion, envelope.kdfAlgorithm, envelope.kdfIterations)

        // KDF 参数已由 validateEnvelope 界定；此处兜底任何派生异常，不允许逃出 import
        val derived = try {
            BackupCrypto.deriveKey(password, salt, envelope.kdfIterations, envelope.kdfKeyLengthBits, testIterations = testIterations)
        } catch (e: Exception) {
            return ImportResult.Corrupted("密钥派生失败：${e.message}")
        }

        val plaintext = try {
            BackupCrypto.decrypt(ciphertext, derived.key, nonce, aad)
        } catch (e: Exception) {
            // GCM AEADBadTagException：密码错误或密文被篡改，无法单凭异常区分
            return ImportResult.AuthenticationFailed("无法解密备份：密码不正确，或文件已损坏、被修改。")
        }

        val payload = try {
            BackupDtos.decodePayload(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            return ImportResult.Corrupted("备份内容解析失败")
        }

        validatePayload(payload)?.let { return it }

        return ImportResult.Ok(payload)
    }
}
