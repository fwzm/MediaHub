package com.mediahub.feature.player.pip

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * PiP 广播接收器的 Manifest 契约（条目 123）。
 *
 * 接收器必须由本库的隐含 manifest 合并进 app，且 **不导出**：
 * 只允许本进程内由 PendingIntent 投递的 PiP 按钮广播进入。
 */
class PictureInPictureManifestTest {

    @Test
    fun `pip action receiver is declared and stays private`() {
        val manifest = featureManifest()
        val receivers = manifest.getElementsByTagName("receiver")
        val receiver = (0 until receivers.length)
            .map { receivers.item(it) as Element }
            .firstOrNull {
                it.androidAttribute("name") == PictureInPictureActionReceiver::class.java.name
            }

        assertNotNull("feature/player 的 manifest 必须声明 PiP 动作接收器", receiver)
        assertEquals(
            "接收器不得导出，避免外部应用伪造播放控制",
            "false",
            receiver!!.androidAttribute("exported"),
        )
    }

    private fun featureManifest(): Document {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("feature/player/src/main/AndroidManifest.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("未找到 feature/player 的 AndroidManifest.xml", file)
        assertTrue("未找到 feature/player 的 AndroidManifest.xml", file!!.isFile)
        return DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
    }

    private fun Element.androidAttribute(name: String): String? =
        getAttributeNS("http://schemas.android.com/apk/res/android", name).takeIf { it.isNotEmpty() }
}
