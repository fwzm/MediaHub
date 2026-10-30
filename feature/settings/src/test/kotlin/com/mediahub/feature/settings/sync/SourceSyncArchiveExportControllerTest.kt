package com.mediahub.feature.settings.sync

import com.mediahub.core.database.sync.SourceSyncArchiveArtifact
import com.mediahub.core.database.sync.SourceSyncArchiveExport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-64 界面侧「导出（另存为文件）」状态机（纯 JVM，用假端口）。
 *
 * 真跑一次 PBKDF2 要 2 秒以上，所以生成端口被替身化；归档字节到底长什么样由
 * core:database 的 SourceSyncArchiveExportTest 用 ArchiveWriter 真写一遍钉住。
 * 这里只钉状态转换与三条边界：口令留空 = 明文导出、失败保留输入框、非就绪状态不吃保存调用。
 */
class SourceSyncArchiveExportControllerTest {

    @Test
    fun requestExpandsTheCardOnlyOnce() {
        val controller = controller()

        controller.request()
        val first = controller.state.value
        controller.request()

        assertTrue(first is SourceSyncArchiveExportState.AwaitingPassphrase);
        assertTrue(controller.state.value is SourceSyncArchiveExportState.AwaitingPassphrase);
    }

    @Test
    fun aBlankPassphraseIsHandedToThePortAsNullAndYieldsPlaintext() = runTest {
        val passphrases = mutableListOf<String?>()
        val controller = controller(
            exportPort = { passphrase ->
                passphrases += passphrase
                SourceSyncArchiveArtifact.Plain("{\"schemaVersion\":1}", 2, 1)
            },
        )
        controller.request()

        controller.prepare("   ")

        // 空白口令一律按「明文导出」处理：空串绝不下发到生成端口。
        assertEquals(listOf<String?>(null), passphrases);
        val ready = controller.state.value as SourceSyncArchiveExportState.Ready
        assertFalse(ready.encrypted);
        assertEquals(SourceSyncArchiveExportController.PLAIN_FILE_NAME, ready.fileName);
        assertEquals("{\"schemaVersion\":1}".toByteArray(Charsets.UTF_8).size, ready.bytes.size);
        assertEquals(2, ready.sourceCount);
        assertEquals(1, ready.skippedCount);
    }

    @Test
    fun aSealedArtifactBecomesReadyWithTheArchiveFileName() = runTest {
        val controller = controller(
            exportPort = { passphrase ->
                assertEquals("correct horse battery staple", passphrase);
                SourceSyncArchiveArtifact.Sealed(archiveBytes(), 3, 0, 1_700_000_000_000L, 600_000)
            },
        )
        controller.request()

        controller.prepare("correct horse battery staple")

        val ready = controller.state.value as SourceSyncArchiveExportState.Ready
        assertTrue(ready.encrypted);
        assertEquals(SourceSyncArchiveExport.DEFAULT_FILE_NAME, ready.fileName);
        assertEquals(archiveBytes().size, ready.bytes.size);
        assertEquals(archiveBytes().size, controller.pendingByteCount);
    }

    @Test
    fun aRejectedArtifactKeepsTheCardOpenWithItsStableCode() = runTest {
        val controller = controller(
            exportPort = { SourceSyncArchiveArtifact.Rejected("passphrase-too-weak", "too weak") },
        )
        controller.request()

        controller.prepare("short")

        val waiting = controller.state.value as SourceSyncArchiveExportState.AwaitingPassphrase
        assertTrue((waiting.error ?: "").contains("passphrase-too-weak"));
        assertEquals(0, controller.pendingByteCount);
    }

    @Test
    fun prepareIsIgnoredBeforeTheCardIsExpanded() = runTest {
        val port = RecordingExportPort(SourceSyncArchiveArtifact.Plain("{}", 0, 0))
        val controller = controller(exportPort = port)

        controller.prepare("correct horse battery staple")

        assertEquals(0, port.calls);
        assertTrue(controller.state.value is SourceSyncArchiveExportState.Idle);
    }

    @Test
    fun aSuccessfulWriteReportsTheFileNameAndTheByteCount() = runTest {
        val controller = controller(
            exportPort = { SourceSyncArchiveArtifact.Plain("{\"a\":1}", 1, 0) },
            filePort = { _, _ -> SourceSyncArchiveWriteResult.Written },
        )
        controller.request()
        controller.prepare("");
        val ready = controller.state.value as SourceSyncArchiveExportState.Ready

        controller.save("content://downloads/sync-envelope.json");

        val saved = controller.state.value as SourceSyncArchiveExportState.Saved
        assertEquals(ready.fileName, saved.fileName);
        assertFalse(saved.encrypted);
        assertEquals(ready.bytes.size, saved.byteCount);
        assertEquals(0, controller.pendingByteCount);
    }

    @Test
    fun anUnavailableStreamReportsItsOwnCode() = runTest {
        val controller = controller(
            exportPort = { SourceSyncArchiveArtifact.Plain("{}", 1, 0) },
            filePort = { _, _ -> SourceSyncArchiveWriteResult.StreamUnavailable },
        )
        controller.request()
        controller.prepare("");

        controller.save("content://downloads/envelope.json");

        val failed = controller.state.value as SourceSyncArchiveExportState.Failed
        assertEquals(SourceSyncArchiveExportController.STREAM_UNAVAILABLE, failed.code);
        assertNull(failed.message);
    }

    @Test
    fun aWriteFailureCarriesItsOwnCode() = runTest {
        val controller = controller(
            exportPort = { SourceSyncArchiveArtifact.Plain("{}", 1, 0) },
            filePort = { _, _ -> SourceSyncArchiveWriteResult.WriteFailed("disk full") },
        )
        controller.request()
        controller.prepare("");

        controller.save("content://downloads/envelope.json");

        val failed = controller.state.value as SourceSyncArchiveExportState.Failed
        assertEquals(SourceSyncArchiveExportController.WRITE_FAILED, failed.code);
        assertEquals("disk full", failed.message);
    }

    @Test
    fun saveWithoutAReadyArtifactNeverTouchesTheFilePort() = runTest {
        val filePort = RecordingFilePort(SourceSyncArchiveWriteResult.Written)
        val controller = controller(filePort = filePort)
        controller.request()

        controller.save("content://downloads/envelope.json");

        assertEquals(0, filePort.calls);
        assertTrue(controller.state.value is SourceSyncArchiveExportState.AwaitingPassphrase);
    }

    @Test
    fun theSavePickerBeingCancelledDropsTheArtifactAndReturnsToIdle() = runTest {
        val controller = controller(exportPort = { SourceSyncArchiveArtifact.Sealed(archiveBytes(), 1, 0, 0L, 600_000) });
        controller.request()
        controller.prepare("correct horse battery staple")
        assertEquals(archiveBytes().size, controller.pendingByteCount);

        controller.saveCancelled()

        assertTrue(controller.state.value is SourceSyncArchiveExportState.Idle);
        assertEquals(0, controller.pendingByteCount);
    }

    @Test
    fun dismissDropsEverythingFromAnyState() = runTest {
        val controller = controller(
            exportPort = { SourceSyncArchiveArtifact.Plain("{}", 1, 0) },
            filePort = { _, _ -> SourceSyncArchiveWriteResult.Written },
        );
        controller.request()
        controller.prepare("");
        controller.save("content://downloads/envelope.json");
        assertTrue(controller.state.value is SourceSyncArchiveExportState.Saved);

        controller.dismiss()

        assertTrue(controller.state.value is SourceSyncArchiveExportState.Idle);
    }

    // ---------------------------------------------------------------- 夹具

    private class RecordingExportPort(
        private val artifact: SourceSyncArchiveArtifact,
    ) : SourceSyncArchiveExportPort {
        var calls = 0

        override suspend fun export(passphrase: String?): SourceSyncArchiveArtifact {
            calls++
            return artifact
        }
    }

    private class RecordingFilePort(
        private val result: SourceSyncArchiveWriteResult,
    ) : SourceSyncArchiveFilePort {
        var calls = 0

        override suspend fun write(uriString: String, bytes: ByteArray): SourceSyncArchiveWriteResult {
            calls++
            return result
        }
    }

    private fun controller(
        exportPort: SourceSyncArchiveExportPort = SourceSyncArchiveExportPort {
            SourceSyncArchiveArtifact.Plain("{}", 0, 0)
        },
        filePort: SourceSyncArchiveFilePort = SourceSyncArchiveFilePort { _, _ ->
            SourceSyncArchiveWriteResult.Written
        },
    ) = SourceSyncArchiveExportController(exportPort, filePort)

    private fun archiveBytes(): ByteArray =
        byteArrayOf(0x4D, 0x48, 0x53, 0x59, 0x4E, 0x43, 0x41, 0x31, 0x00, 0x00, 0x00, 0x10)
}
