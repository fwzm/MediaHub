package com.mediahub.core.common.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「电脑导出、手机导入」的真文件证据（W25）：
 * 被测文件 `windows-cli-export.mhbackup` 是 Windows 端 `MediaHub.BackupCli export` 在 2026-10-30 实际写出的字节，
 * 不是测试自己拼的容器。这里只做读取与断言，不重新生成——一旦 Windows 侧改动，这份文件必须重跑 CLI 再落盘。
 *
 * 同时校验两件跨端容易错的事：空账号必须为 null（Android 的身份比较里 null 与空串不同），
 * 以及 LOCAL 来源不能带端点。
 */
class BackupWindowsCliFileTest {

    private val password = "跨平台备份-测试口令-2026".toCharArray()

    private val bytes: ByteArray by lazy {
        val stream = BackupWindowsCliFileTest::class.java
            .getResourceAsStream("/backup/windows-cli-export.mhbackup")
        checkNotNull(stream) { "missing test resource /backup/windows-cli-export.mhbackup" }
        stream.readBytes()
    }

    private fun load(): BackupDtos.BackupPayload {
        val result = BackupSerializer.import(bytes, password)
        assertTrue("期望 Ok，实际 " + result, result is BackupSerializer.ImportResult.Ok)
        return (result as BackupSerializer.ImportResult.Ok).payload
    }

    @Test
    fun theWindowsCliExportOpensOnAndroidWithItsManifestIntact() {
        val payload = load()
        val manifest = payload.manifest
        assertEquals(BackupCrypto.FORMAT_VERSION, manifest.formatVersion)
        assertEquals(BackupCrypto.MINIMUM_READER_VERSION, manifest.minimumReaderVersion)
        assertEquals("windows-0.1.0", manifest.appVersion)
        assertEquals(listOf("servers", "preferences"), manifest.includedSections)
        assertEquals(mapOf("servers" to 4, "preferences" to 1), manifest.recordCounts)
        assertTrue(manifest.createdAtEpochMs > 0L)
    }

    @Test
    fun theServersKeepTheirIdentitiesAndTheBlankAccountStaysNoAccount() {
        val servers = load().servers
        assertEquals(4, servers.size)
        val byType = servers.associateBy { it.type }

        val emby = byType.getValue("EMBY")
        assertEquals("sync-abc", emby.backupId)
        assertEquals("Home Emby", emby.name)
        assertEquals("user-A", emby.username)
        assertTrue(emby.isDefault)
        assertEquals(0, emby.sortOrder)
        assertEquals(1, emby.endpoints.size)
        assertEquals("https://media.example:8920/emby/", emby.endpoints[0].url)
        assertTrue(emby.endpoints[0].isPrimary)
        assertTrue(emby.endpoints[0].enabled)

        val jellyfin = byType.getValue("JELLYFIN")
        assertNull(jellyfin.username)
        assertEquals("Jelly", jellyfin.name)
        assertEquals(20, jellyfin.backupId.length)
        assertTrue(jellyfin.backupId.startsWith("win-"))

        val webdav = byType.getValue("WEBDAV")
        assertEquals("Team DAV", webdav.name)
        assertNull(webdav.username)
        assertEquals("https://dav.example/dav/films", webdav.endpoints[0].url)
    }

    @Test
    fun theLocalFolderSourceCarriesNoEndpointAndNoAccount() {
        val local = load().servers.single { it.type == "LOCAL" }
        assertEquals("我的本地文件夹", local.name)
        assertTrue("LOCAL 来源不应带端点", local.endpoints.isEmpty())
        assertNull(local.username)
    }

    @Test
    fun thePreferencesKeepOnlyTheFieldsWithRealEquivalents() {
        val preferences = load().preferences
        assertNotNull(preferences)
        assertEquals(1.5f, preferences!!.defaultPlaybackSpeed, 0.0001f)
        assertEquals(false, preferences.autoPlayNextEpisode)
        assertEquals(true, preferences.showPlayerInfoOverlay)
        assertEquals(18, preferences.subtitleSizeSp)
    }

    @Test
    fun theComputerExportDoesNotSmugglePlaybackProgressOrFavorites() {
        val payload = load()
        assertTrue("Windows 按路径记账，没有可映射的条目 id，因此不导出播放记录", payload.progress.isEmpty())
        assertTrue("收藏是服务端能力，导出时不读也不编", payload.favorites.isEmpty())
    }

    @Test
    fun theSameFileWithAnotherPasswordDoesNotOpen() {
        val result = BackupSerializer.import(bytes, "另一个口令".toCharArray())
        assertTrue("错口令必须解不开，实际 " + result, result is BackupSerializer.ImportResult.AuthenticationFailed)
    }
}
