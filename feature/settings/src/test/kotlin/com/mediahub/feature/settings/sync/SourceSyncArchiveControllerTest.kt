package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.database.sync.SourceSyncDocumentInput
import com.mediahub.core.database.sync.SourceSyncDocumentResolution
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-63 界面侧口令流程（纯 JVM，用假端口）：嗅探分流、解开后交回明文、失败保留输入框。
 *
 * 真跑一次 PBKDF2-SHA256 600000 轮太慢，所以端口被替身化；归档字节本身怎么解由
 * core:database 的 SourceSyncArchiveImportTest 用 Windows 写出的真归档钉住。
 */
class SourceSyncArchiveControllerTest {

    @Test
    fun plaintextIsNotOfferedAndLeavesNoPrompt() {
        val controller = controller(port = { _, _ -> plaintext("{ }") })

        val offered = controller.offer(plainEnvelopeBytes(), "envelope.json")

        assertFalse(offered)
        assertFalse(controller.prompt.value.visible)
        assertEquals(0, controller.pendingByteCount)
    }

    @Test
    fun anArchiveShowsThePromptAndAsksForThePassphrase() {
        val blocked = mutableListOf<Triple<String, String?, String>>()
        val controller = controller(
            port = { _, _ -> plaintext("{}") },
            onBlocked = { reason, message, fileName -> blocked += Triple(reason, message, fileName) },
        )

        val offered = controller.offer(archiveBytes(), "backup.mharchive")

        assertTrue(offered)
        val prompt = controller.prompt.value
        assertTrue(prompt.visible)
        assertEquals("backup.mharchive", prompt.fileName)
        assertNull(prompt.error)
        assertEquals(1, blocked.size)
        assertEquals(SourceSyncDocumentInput.PASSPHRASE_REQUIRED, blocked[0].first)
        assertEquals("backup.mharchive", blocked[0].third)
        // 暂存只为解密用：界面显示的是文件名与字节数，永远不回显内容。
        assertEquals(archiveBytes().size, controller.pendingByteCount)
    }

    @Test
    fun aCorrectPassphraseHandsBackTheEnvelopeAndDropsTheArchive() = runTest {
        val unlocked = mutableListOf<Pair<String, String>>()
        val controller = controller(
            port = { _, passphrase ->
                assertEquals("correct horse battery staple", passphrase)
                decrypted("{\"schemaVersion\":1}")
            },
            onUnlocked = { json, fileName -> unlocked += json to fileName },
        )
        controller.offer(archiveBytes(), "backup.mharchive")

        controller.unlock("correct horse battery staple")

        assertEquals(listOf("{\"schemaVersion\":1}" to "backup.mharchive"), unlocked)
        assertFalse(controller.prompt.value.visible)
        assertEquals(0, controller.pendingByteCount)
    }

    @Test
    fun aWrongPassphraseKeepsThePromptAndPutsTheArchiveCodeOnTheErrorLine() = runTest {
        val blocked = mutableListOf<Triple<String, String?, String>>()
        val controller = controller(
            port = { _, _ -> rejected("wrong-passphrase-or-tampered") },
            onBlocked = { reason, message, fileName -> blocked += Triple(reason, message, fileName) },
        )
        controller.offer(archiveBytes(), "backup.mharchive")
        blocked.clear()

        controller.unlock("a different long passphrase")

        val prompt = controller.prompt.value
        assertTrue(prompt.visible)
        assertEquals("解密失败（wrong-passphrase-or-tampered）", prompt.error)
        assertEquals(1, blocked.size)
        assertEquals(SourceSyncImportCopy.UNLOCK_FAILED, blocked[0].first)
        // 失败后归档仍在手里，改一个字符就能重试，不用重新选文件。
        assertEquals(archiveBytes().size, controller.pendingByteCount)
    }

    @Test
    fun aBlankPassphraseNeverReachesThePort() = runTest {
        val port = RecordingPort(decrypted("{}"))
        val controller = controller(port = port)
        controller.offer(archiveBytes(), "backup.mharchive")

        controller.unlock("   ")

        assertEquals(0, port.calls)
        assertTrue(controller.prompt.value.visible)
        assertEquals(archiveBytes().size, controller.pendingByteCount)
    }

    @Test
    fun unlockWithoutAnOfferedArchiveDoesNothing() = runTest {
        val port = RecordingPort(decrypted("{}"))
        val controller = controller(port = port)

        controller.unlock("correct horse battery staple")

        assertEquals(0, port.calls)
        assertFalse(controller.prompt.value.visible)
    }

    @Test
    fun clearDropsTheStashedArchiveAndThePrompt() {
        val controller = controller(port = { _, _ -> plaintext("{}") })
        controller.offer(archiveBytes(), "backup.mharchive")

        controller.clear()

        assertEquals(0, controller.pendingByteCount)
        assertFalse(controller.prompt.value.visible)
        assertNull(controller.prompt.value.fileName)
    }

    @Test
    fun pickingAnotherFileClearsThePreviousPrompt() {
        val controller = controller(port = { _, _ -> plaintext("{}") })
        controller.offer(archiveBytes(), "first.mharchive")

        val offered = controller.offer(plainEnvelopeBytes(), "second.json")

        assertFalse(offered)
        assertFalse(controller.prompt.value.visible)
        assertEquals(0, controller.pendingByteCount)
    }

    // ---------------------------------------------------------------- 夹具

    private class RecordingPort(
        private val resolution: SourceSyncDocumentResolution,
    ) : SourceSyncArchiveUnlockPort {
        var calls = 0

        override fun unlock(archiveBytes: ByteArray, passphrase: String): SourceSyncDocumentResolution {
            calls++
            return resolution
        }
    }

    private fun controller(
        port: SourceSyncArchiveUnlockPort,
        onUnlocked: (String, String) -> Unit = { _, _ -> },
        onBlocked: (String, String?, String) -> Unit = { _, _, _ -> },
    ) = SourceSyncArchiveController(port, onUnlocked, onBlocked)

    private fun plaintext(text: String) = SourceSyncDocumentResolution.Plaintext(text)

    private fun decrypted(json: String) = SourceSyncDocumentResolution.Decrypted(
        envelope = SourceSyncEnvelope(deviceId = "device-a", snapshotId = "snap-1"),
        json = json,
    )

    private fun rejected(reason: String) =
        SourceSyncDocumentResolution.Rejected(reason, "archive could not be read")

    private fun archiveBytes(): ByteArray =
        byteArrayOf(0x4D, 0x48, 0x53, 0x59, 0x4E, 0x43, 0x41, 0x31, 0x00, 0x00, 0x00, 0x10)

    private fun plainEnvelopeBytes(): ByteArray =
        "{\"schemaVersion\":1}".toByteArray(Charsets.UTF_8)
}