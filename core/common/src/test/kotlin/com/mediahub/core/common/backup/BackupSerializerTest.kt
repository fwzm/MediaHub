package com.mediahub.core.common.backup

import org.junit.Assert.assertEquals
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
}
