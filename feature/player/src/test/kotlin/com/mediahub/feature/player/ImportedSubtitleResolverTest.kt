package com.mediahub.feature.player

import android.net.Uri
import com.mediahub.provider.api.DiscoveredSubtitle
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 真实文件 URI -> 读字节 -> 编码归一化 -> 缓存文件 URI 的完整路径。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportedSubtitleResolverTest {

    @Test
    fun `file subtitle is converted to a utf8 cache file`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "gbk-fixture.srt").apply {
            writeBytes("1\n00:00:01,000 --> 00:00:02,000\n你好\n".toByteArray(Charset.forName("GBK")))
        }
        val uri = Uri.fromFile(source).toString()
        val candidate = DiscoveredSubtitle(
            id = uri, name = "gbk-fixture", fileName = "gbk-fixture.srt",
            extension = "srt", language = "zh", uri = uri,
        )
        val prepared = ImportedSubtitleResolver(context).prepare(candidate)
        assertNotEquals(candidate.uri, prepared.uri)
        assertTrue(prepared.uri.startsWith("file:"))
        val text = File(Uri.parse(prepared.uri).path!!).readText()
        assertTrue(text.contains("你好"))
        assertEquals(candidate.id, prepared.id)
    }
}
