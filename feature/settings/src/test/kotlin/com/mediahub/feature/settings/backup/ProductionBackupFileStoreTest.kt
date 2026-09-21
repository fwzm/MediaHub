package com.mediahub.feature.settings.backup

import android.net.Uri
import com.mediahub.core.common.AppDispatchers
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
    fun `backup app version follows installed package metadata`() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName = "9.8-review-build"
        shadowOf(context.packageManager).installPackage(packageInfo)

        assertEquals("9.8-review-build", backupAppVersion(context))
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
    fun `oversize stream stops at the limit plus one byte without consuming remaining data`() = runBlocking {
        val uri = Uri.parse("content://backup-test/oversize-counting")
        var consumed = 0
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = error("Use bulk reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                consumed += length
                return length
            }
            override fun close() { closed = true }
        }
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(uri, stream)

        val result = store.readBounded(uri.toString(), maxBytes = 16)

        assertEquals(BackupFileStore.ReadResult.TooLarge, result)
        assertTrue("读取只需一个额外字节确认超限", consumed <= 17)
        assertTrue("超限时仍应关闭流", closed)
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

    @Test
    fun `input close failure is typed and never exposes provider exception text`() = runBlocking {
        val uri = Uri.parse("content://backup-test/close-failure")
        val stream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun close() { throw IOException("Authorization=private-close-secret") }
        }
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(uri, stream)

        val result = store.readBounded(uri.toString())

        assertTrue("关闭失败不能逃逸为未处理异常或返回成功", result is BackupFileStore.ReadResult.ReadFailed)
        assertFalse(result.toString().contains("private-close-secret"))
    }

    @Test
    fun `read error never exposes private document path`() = runBlocking {
        val file = File(tempDir, "Cookie=private-read-secret.mhb")

        val result = store.readBounded(Uri.fromFile(file).toString())

        assertTrue(result is BackupFileStore.ReadResult.ReadFailed)
        assertFalse("提供方异常可能含URI或凭据", result.toString().contains("private-read-secret"))
    }

    @Test
    fun `write error never exposes private document path`() = runBlocking {
        val uri = Uri.parse("content://backup-test/write-failure")
        var writes = 0
        var closes = 0
        val stream = object : OutputStream() {
            override fun write(value: Int) {
                writes += 1
                throw IOException("provider/private/Authorization=private-write-secret.mhb")
            }
            override fun close() { closes += 1 }
        }
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerOutputStream(uri, stream)

        val result = store.write(uri.toString(), byteArrayOf(1))

        assertTrue("实际注入的提供方写入错误必须类型化：$result", result is BackupFileStore.WriteResult.WriteFailed)
        assertFalse("提供方异常可能含URI或凭据", result.toString().contains("private-write-secret"))
        assertEquals("真实写入路径已执行", 1, writes)
        assertEquals("写入失败也关闭流", 1, closes)
    }

    @Test
    fun `read checks cancellation before consuming another chunk and always closes stream`() = runBlocking {
        val uri = Uri.parse("content://backup-test/cancelled-read")
        val operation = Job()
        var reads = 0
        var closes = 0
        val stream = object : InputStream() {
            override fun read(): Int = error("Use bulk reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads += 1
                if (reads == 1) {
                    operation.cancel()
                    return 0
                }
                throw IOException("Must not continue reading after cancellation")
            }
            override fun close() { closes += 1 }
        }
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerInputStream(uri, stream)

        try {
            val outcome = runCatching { withContext(operation) { store.readBounded(uri.toString()) } }
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertEquals("取消后不消费下一块", 1, reads)
            assertEquals("取消仍关闭输入流", 1, closes)
        } finally {
            operation.cancel()
        }
    }
}
