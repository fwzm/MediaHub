package com.mediahub.feature.settings.backup

import android.net.Uri
import com.mediahub.core.common.AppDispatchers
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ProductionBackupFileStore 生产实现回归（Phase 1I review round-3）：
 * 真实 ContentResolver + file:// URI 验证分块有界读取与写入语义——
 * 超限立即终止（TooLarge）、空文件（Empty）、读取失败（ReadFailed）、
 * 写入内容一致；不经任何 fake。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProductionBackupFileStoreTest {

    private lateinit var store: ProductionBackupFileStore
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        store = ProductionBackupFileStore(context, AppDispatchers())
        tempDir = context.cacheDir.resolve("backup-filestore-test").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun newFile(name: String, content: ByteArray): File {
        val f = File(tempDir, name)
        f.writeBytes(content)
        return f
    }

    @Test
    fun `readBounded reads full content and resolves file name`() = runBlocking {
        // 覆盖分块边界：内容 > 64KB 缓冲，含多字节字符
        val content = ByteArray(64 * 1024 + 1234) { (it % 251).toByte() } + "备份完成 ✓".toByteArray(Charsets.UTF_8)
        val file = newFile("MediaHub-backup-full.mhb", content)

        val result = store.readBounded(Uri.fromFile(file).toString())

        assertTrue(result is BackupFileStore.ReadResult.Read)
        result as BackupFileStore.ReadResult.Read
        assertArrayEquals("分块读取内容一致", content, result.bytes)
        assertEquals("文件名取自路径末段", "MediaHub-backup-full.mhb", result.fileName)
    }

    @Test
    fun `readBounded stops early with TooLarge when maxBytes exceeded`() = runBlocking {
        val file = newFile("oversize.mhb", ByteArray(4096))

        val result = store.readBounded(Uri.fromFile(file).toString(), maxBytes = 16)

        assertEquals("超限立即终止，不读完全量", BackupFileStore.ReadResult.TooLarge, result)
    }

    @Test
    fun `readBounded exactly at limit succeeds - boundary`() = runBlocking {
        val file = newFile("exact.mhb", ByteArray(16))

        val result = store.readBounded(Uri.fromFile(file).toString(), maxBytes = 16)

        assertTrue(result is BackupFileStore.ReadResult.Read)
        assertEquals(16, (result as BackupFileStore.ReadResult.Read).bytes.size)
    }

    @Test
    fun `readBounded returns Empty for zero-byte file`() = runBlocking {
        val file = newFile("empty.mhb", ByteArray(0))

        val result = store.readBounded(Uri.fromFile(file).toString())

        assertEquals(BackupFileStore.ReadResult.Empty, result)
    }

    @Test
    fun `readBounded reports ReadFailed for missing file`() = runBlocking {
        val missing = File(tempDir, "no-such-backup.mhb")

        val result = store.readBounded(Uri.fromFile(missing).toString())

        assertTrue("读取失败须类型化报因，不得转成空字节", result is BackupFileStore.ReadResult.ReadFailed)
    }

    @Test
    fun `write persists exact bytes and reports Written`() = runBlocking {
        val target = File(tempDir, "export-target.mhb")
        val bytes = ByteArray(5000) { (it % 97).toByte() }

        val result = store.write(Uri.fromFile(target).toString(), bytes)

        assertEquals(BackupFileStore.WriteResult.Written, result)
        assertArrayEquals("写出的字节与导出一致", bytes, target.readBytes())
    }
}
