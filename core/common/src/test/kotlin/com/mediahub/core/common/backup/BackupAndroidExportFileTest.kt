package com.mediahub.core.common.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「手机导出、电脑与手机都读得懂」的真文件证据（W28）：
 * 被测文件 /backup/android-export.mhbackup 由 Android 生产导出路径
 * （BackupRepository.exportBackup，真 Robolectric Room 库）在 2026-10-30 实际写出，
 * 不是测试里拼的容器；同一份字节也落在 windows/tests/assets，供 Windows 侧读一次。
 *
 * 生成者：feature/settings 的 BackupAndroidExportRoundTripTest.theExportCanBeRegeneratedFromARealRoomLibrary。
 * 改动导出格式后必须重跑该用例再把 output 复制到两端资源目录，不得手改字节。
 */
class BackupAndroidExportFileTest {

    private val password = "跨平台备份-测试口令-2026".toCharArray()

    private val bytes: ByteArray by lazy {
        val stream = BackupAndroidExportFileTest::class.java
            .getResourceAsStream("/backup/android-export.mhbackup")
        checkNotNull(stream) { "missing test resource /backup/android-export.mhbackup" }
        stream.readBytes()
    }

    private fun load(): BackupDtos.BackupPayload {
        val result = BackupSerializer.import(bytes, password)
        assertTrue("期望 Ok，实际 " + result, result is BackupSerializer.ImportResult.Ok)
        return (result as BackupSerializer.ImportResult.Ok).payload
    }

    @Test
    fun theAndroidExportOpensWithItsManifestIntact() {
        val manifest = load().manifest
        assertEquals(BackupCrypto.FORMAT_VERSION, manifest.formatVersion)
        assertEquals(BackupCrypto.MINIMUM_READER_VERSION, manifest.minimumReaderVersion)
        assertEquals("android-0.1.0", manifest.appVersion)
        assertEquals(listOf("servers", "progress", "preferences"), manifest.includedSections)
        assertEquals(mapOf("servers" to 4, "progress" to 2, "preferences" to 1), manifest.recordCounts)
        assertTrue(manifest.createdAtEpochMs > 0L)
    }

    @Test
    fun theServersKeepTheirIdentitiesAndTheLocalSourceCarriesNoEndpoint() {
        val servers = load().servers
        assertEquals(4, servers.size)
        val byType = servers.associateBy { it.type }

        val emby = byType.getValue("EMBY")
        assertEquals("srv-emby", emby.backupId)
        assertEquals("Home Emby", emby.name)
        assertEquals("user-A", emby.username)
        assertEquals("客厅", emby.note)
        assertTrue(emby.isDefault)
        assertEquals(0, emby.sortOrder)
        assertEquals("https://media.example:8920/emby/", emby.endpoints.single().url)

        val jellyfin = byType.getValue("JELLYFIN")
        assertEquals("srv-jellyfin", jellyfin.backupId)
        assertNull("空账号必须保持 null，不能是空串", jellyfin.username)
        assertFalse(jellyfin.isDefault)
        assertEquals(1, jellyfin.sortOrder)

        assertEquals("Team DAV", byType.getValue("WEBDAV").name)

        val local = byType.getValue("LOCAL")
        assertEquals("我的本地文件夹", local.name)
        assertTrue("LOCAL 来源不携带设备绑定端点", local.endpoints.isEmpty())
    }

    @Test
    fun thePlaybackRecordsKeepTheirPositionsTitlesAndOwningSource() {
        val progress = load().progress
        assertEquals(2, progress.size)

        val first = progress.single { it.itemId == "item-001" }
        assertEquals("srv-emby", first.serverBackupId)
        assertEquals(540_000L, first.positionMs)
        assertEquals(1_440_000L, first.durationMs)
        assertTrue(first.isPaused)
        assertEquals(1_790_000_000_000L, first.updatedAtEpochMs)
        assertEquals("第一集", first.itemTitle)
        assertEquals("EPISODE", first.itemType)

        val second = progress.single { it.itemId == "item-002" }
        assertEquals(1_200_000L, second.positionMs)
        assertFalse(second.isPaused)
    }

    @Test
    fun thePreferencesKeepTheWhitelistedPlaybackChoices() {
        val preferences = load().preferences
        assertEquals("MEDIA3", preferences!!.playbackEngineMode)
        assertEquals(1.5f, preferences.defaultPlaybackSpeed, 0.0001f)
        assertEquals(22, preferences.subtitleSizeSp)
        assertFalse(preferences.autoPlayNextEpisode)
        assertTrue(preferences.showPlayerInfoOverlay)
        assertEquals(0xFF00FF00.toInt(), preferences.subtitleStyle.textColor)
    }

    @Test
    fun theAndroidExportNeverClaimsToCarryFavoritesItDoesNotHave() {
        val payload = load()
        assertTrue("收藏真源在服务端，导出时不读也不编", payload.favorites.isEmpty())
        assertFalse(payload.manifest.includedSections.contains("favorites"))
    }

    @Test
    fun theSameFileWithAnotherPasswordDoesNotOpen() {
        val result = BackupSerializer.import(bytes, "另一个口令".toCharArray())
        assertTrue(
            "另一个口令必须解不开，实际 " + result,
            result is BackupSerializer.ImportResult.AuthenticationFailed,
        )
    }
}
