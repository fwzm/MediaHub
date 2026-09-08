package com.mediahub.core.common.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSerializerTest {

    private fun password() = "test-pw-${System.identityHashCode(this)}".toCharArray()

    private fun samplePayload() = BackupDtos.BackupPayload(
        manifest = BackupFileFormat.Manifest(
            formatVersion = 1,
            minimumReaderVersion = 1,
            appVersion = "0.1.0-alpha.1",
            createdAtEpochMs = 1_700_000_000_000,
            includedSections = listOf("servers", "progress", "preferences"),
            recordCounts = mapOf("servers" to 1, "progress" to 1),
        ),
        servers = listOf(
            BackupDtos.ServerDto(
                backupId = "srv-1", name = "Test Emby", type = "EMBY",
                username = "user-A", isDefault = true, sortOrder = 0,
                endpoints = listOf(
                    BackupDtos.EndpointDto(name = "默认线路", url = "https://media.example:8920", isPrimary = true, enabled = true, sortOrder = 0),
                ),
            ),
        ),
        progress = listOf(
            BackupDtos.ProgressDto(
                serverBackupId = "srv-1", itemId = "item-1",
                positionMs = 90_000, durationMs = 600_000,
                isPaused = false, updatedAtEpochMs = 1_700_000_000_000,
                itemTitle = "Test Item",
            ),
        ),
        preferences = BackupDtos.PreferencesDto(defaultPlaybackSpeed = 1.5f),
    )

    @Test
    fun `export import roundtrip preserves payload`() {
        val original = samplePayload()
        val bytes = BackupSerializer.export(original, password())
        val result = BackupSerializer.import(bytes, password())

        assertTrue(result is BackupSerializer.ImportResult.Ok)
        val payload = (result as BackupSerializer.ImportResult.Ok).payload
        assertEquals(original.manifest.appVersion, payload.manifest.appVersion)
        assertEquals(original.servers.size, payload.servers.size)
        assertEquals(original.servers[0].backupId, payload.servers[0].backupId)
        assertEquals(original.servers[0].endpoints[0].url, payload.servers[0].endpoints[0].url)
        assertEquals(original.progress[0].itemId, payload.progress[0].itemId)
        assertEquals(original.progress[0].positionMs, payload.progress[0].positionMs)
        assertEquals(original.preferences?.defaultPlaybackSpeed, payload.preferences?.defaultPlaybackSpeed)
    }

    @Test
    fun `wrong password returns AuthenticationFailed`() {
        val bytes = BackupSerializer.export(samplePayload(), password())
        val wrongPw = "different-${System.identityHashCode(this)}".toCharArray()
        val result = BackupSerializer.import(bytes, wrongPw)
        assertTrue(result is BackupSerializer.ImportResult.AuthenticationFailed)
    }

    @Test
    fun `tampered ciphertext returns InvalidPassword`() {
        val bytes = BackupSerializer.export(samplePayload(), password())
        // 解析 envelope → 篡改密文字节 → 重新序列化
        val envelope = BackupFileFormat.decodeEnvelope(bytes.decodeToString())
        val ct = BackupFileFormat.decodeB64(envelope.ciphertextB64)
        ct[ct.size - 1] = (ct[ct.size - 1].toInt() xor 0x01).toByte()
        val tampered = BackupFileFormat.Envelope(
            magic = envelope.magic, formatVersion = envelope.formatVersion,
            kdfAlgorithm = envelope.kdfAlgorithm, kdfSaltB64 = envelope.kdfSaltB64,
            kdfIterations = envelope.kdfIterations, kdfKeyLengthBits = envelope.kdfKeyLengthBits,
            nonceB64 = envelope.nonceB64,
            ciphertextB64 = BackupFileFormat.encodeB64(ct),
        )
        val tamperedBytes = BackupFileFormat.encodeEnvelope(tampered).toByteArray()
        val result = BackupSerializer.import(tamperedBytes, password())
        assertTrue(result is BackupSerializer.ImportResult.AuthenticationFailed)
    }

    @Test
    fun `truncated file returns Corrupted or NotABackupFile`() {
        val bytes = BackupSerializer.export(samplePayload(), password())
        val truncated = bytes.copyOf(bytes.size / 2)
        val result = BackupSerializer.import(truncated, password())
        assertTrue(
            "截断文件应报 Corrupted 或 NotABackupFile",
            result is BackupSerializer.ImportResult.Corrupted || result is BackupSerializer.ImportResult.NotABackupFile,
        )
    }

    @Test
    fun `non backup file returns NotABackupFile`() {
        val result = BackupSerializer.import("just some text file".toByteArray(), password())
        assertTrue(result is BackupSerializer.ImportResult.NotABackupFile)
    }

    @Test
    fun `version too new returns VersionTooNew`() {
        // 手动构造一个 formatVersion=99 的 envelope
        val envelope = BackupFileFormat.Envelope(
            magic = BackupCrypto.MAGIC, formatVersion = 99,
            kdfAlgorithm = "PBKDF2WithHmacSHA256",
            kdfSaltB64 = BackupFileFormat.encodeB64(BackupCrypto.randomSalt()),
            kdfIterations = BackupCrypto.PBKDF2_ITERATIONS,
            kdfKeyLengthBits = 256,
            nonceB64 = BackupFileFormat.encodeB64(BackupCrypto.randomNonce()),
            ciphertextB64 = BackupFileFormat.encodeB64("fake".toByteArray()),
        )
        val bytes = BackupFileFormat.encodeEnvelope(envelope).toByteArray()
        val result = BackupSerializer.import(bytes, password())
        assertTrue(result is BackupSerializer.ImportResult.VersionTooNew)
    }

    @Test
    fun `exported bytes contain no plaintext url or username`() {
        val bytes = BackupSerializer.export(samplePayload(), password())
        val text = bytes.decodeToString()
        // AES-GCM 加密后 URL 和用户名不应以明文出现
        assertTrue("URL 不应明文暴露", !text.contains("media.example"))
        assertTrue("用户名不应明文暴露", !text.contains("user-A"))
        assertTrue("服务器名不应明文暴露", !text.contains("Test Emby"))
    }

    // ---- 反向用例矩阵（Phase 1I review：非法载荷零写入、错误类型化、不允许异常逃出 import） ----

    /** 低成本 KDF 迭代（仅测试）：export/import 两侧一致即可解密。 */
    private fun fastExport(payload: BackupDtos.BackupPayload, pw: CharArray = password()): ByteArray =
        BackupSerializer.export(payload, pw, testIterations = 10_000)

    private fun fastImport(bytes: ByteArray, pw: CharArray = password()) =
        BackupSerializer.import(bytes, pw, testIterations = 10_000)

    private fun withManifest(
        base: BackupDtos.BackupPayload = samplePayload(),
        transform: (BackupFileFormat.Manifest) -> BackupFileFormat.Manifest,
    ) = base.copy(manifest = transform(base.manifest))

    @Test
    fun `short salt envelope returns Corrupted - not a crash`() {
        val bytes = fastExport(samplePayload())
        val envelope = BackupFileFormat.decodeEnvelope(bytes.decodeToString())
        val tampered = envelope.copy(kdfSaltB64 = BackupFileFormat.encodeB64(ByteArray(8)))
        val result = fastImport(BackupFileFormat.encodeEnvelope(tampered).toByteArray())
        assertTrue("salt 过短应报 Corrupted", result is BackupSerializer.ImportResult.Corrupted)
        assertTrue("错误应说明 salt", (result as BackupSerializer.ImportResult.Corrupted).reason.contains("salt"))
    }

    @Test
    fun `invalid base64 salt returns Corrupted`() {
        val bytes = fastExport(samplePayload())
        val envelope = BackupFileFormat.decodeEnvelope(bytes.decodeToString())
        val tampered = envelope.copy(kdfSaltB64 = "not-base64!!!")
        val result = fastImport(BackupFileFormat.encodeEnvelope(tampered).toByteArray())
        assertTrue(result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `wrong nonce length returns Corrupted`() {
        val bytes = fastExport(samplePayload())
        val envelope = BackupFileFormat.decodeEnvelope(bytes.decodeToString())
        val tampered = envelope.copy(nonceB64 = BackupFileFormat.encodeB64(ByteArray(8)))
        val result = fastImport(BackupFileFormat.encodeEnvelope(tampered).toByteArray())
        assertTrue("nonce 长度非法应报 Corrupted", result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `minimumReaderVersion above reader returns VersionTooNew`() {
        val payload = withManifest {
            it.copy(formatVersion = 1, minimumReaderVersion = BackupCrypto.FORMAT_VERSION + 1)
        }
        val result = fastImport(fastExport(payload))
        assertTrue("读不了的时代应报版本过新", result is BackupSerializer.ImportResult.VersionTooNew)
        assertEquals(
            "报告的文件版本 = minimumReaderVersion",
            BackupCrypto.FORMAT_VERSION + 1,
            (result as BackupSerializer.ImportResult.VersionTooNew).fileVersion,
        )
    }

    @Test
    fun `minimumReaderVersion below 1 returns Corrupted`() {
        val payload = withManifest { it.copy(minimumReaderVersion = 0) }
        val result = fastImport(fastExport(payload))
        assertTrue(result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `unknown section returns Corrupted`() {
        val payload = withManifest { it.copy(includedSections = listOf("servers", "secret_section")) }
        val result = fastImport(fastExport(payload))
        assertTrue(result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `record count mismatch returns Corrupted`() {
        val payload = withManifest { it.copy(recordCounts = mapOf("servers" to 5, "progress" to 1)) }
        val result = fastImport(fastExport(payload))
        assertTrue("计数与实际不符应拒绝", result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `duplicate server ids return Corrupted`() {
        val base = samplePayload()
        val duplicated = base.copy(servers = base.servers + base.servers.first())
        val payload = withManifest(duplicated) {
            it.copy(recordCounts = mapOf("servers" to duplicated.servers.size, "progress" to duplicated.progress.size))
        }
        val result = fastImport(fastExport(payload))
        assertTrue("重复服务器 ID 应拒绝", result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `duplicate progress keys return Corrupted`() {
        val base = samplePayload()
        val duplicated = base.copy(progress = base.progress + base.progress.first())
        val payload = withManifest(duplicated) {
            it.copy(recordCounts = mapOf("servers" to duplicated.servers.size, "progress" to duplicated.progress.size))
        }
        val result = fastImport(fastExport(payload))
        assertTrue("重复播放记录应拒绝", result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `negative position returns Corrupted`() {
        val base = samplePayload()
        val bad = base.copy(progress = base.progress.map { it.copy(positionMs = -1) })
        val result = fastImport(fastExport(bad))
        assertTrue("负时长字段应拒绝", result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `url with user-info credentials rejected on import`() {
        val base = samplePayload()
        val dirty = base.copy(
            servers = base.servers.map {
                it.copy(endpoints = it.endpoints.map { ep -> ep.copy(url = "https://user:fake-credential@example.com") })
            },
        )
        val result = fastImport(fastExport(dirty))
        assertTrue("user-info 夹带凭据应拒绝导入", result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `url with sensitive query rejected on import`() {
        val base = samplePayload()
        val dirty = base.copy(
            servers = base.servers.map {
                it.copy(endpoints = it.endpoints.map { ep -> ep.copy(url = "https://example.com?api_key=FAKE-KEY-123") })
            },
        )
        val result = fastImport(fastExport(dirty))
        assertTrue("敏感 query 参数应拒绝导入", result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `orphan progress is not a format error - surfaced for preview`() {
        val base = samplePayload()
        val orphaned = base.copy(
            progress = base.progress + base.progress.first().copy(serverBackupId = "srv-missing", itemId = "item-orphan"),
        )
        val payload = withManifest(orphaned) {
            it.copy(recordCounts = mapOf("servers" to orphaned.servers.size, "progress" to orphaned.progress.size))
        }
        val result = fastImport(fastExport(payload))
        assertTrue("孤立引用属冲突披露，不是格式错误", result is BackupSerializer.ImportResult.Ok)
        assertEquals(2, (result as BackupSerializer.ImportResult.Ok).payload.progress.size)
    }

    @Test
    fun `oversize input returns Corrupted before parsing`() {
        val oversized = ByteArray(BackupSerializer.MAX_FILE_BYTES + 1)
        val result = fastImport(oversized)
        assertTrue("超限文件应提前拒绝", result is BackupSerializer.ImportResult.Corrupted)
    }

    @Test
    fun `export enforces max bytes so files satisfy own import limit`() {
        assertThrows(IllegalStateException::class.java) {
            BackupSerializer.export(samplePayload(), password(), maxBytes = 64, testIterations = 10_000)
        }
    }
}
