package com.mediahub.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 入口 Manifest 契约（条目 30 / 31）。
 *
 * 真机进入 PiP 依赖三件事同时成立：Activity 声明 supportsPictureInPicture、
 * 允许 resize、以及 configChanges 覆盖 PiP 改尺寸/转方向，避免播放被重建。
 */
class PlayerPictureInPictureManifestTest {

    @Test
    fun `main activity enables picture in picture without recreating on resize`() {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile())

        val activities = document.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) as Element }
            .firstOrNull { it.androidAttribute("name") == ".MainActivity" }

        assertNotNull("Manifest 缺少 .MainActivity", mainActivity)
        assertEquals(
            "缺 supportsPictureInPicture=true 时 enterPictureInPictureMode 直接返回 false",
            "true",
            mainActivity!!.androidAttribute("supportsPictureInPicture"),
        )
        assertEquals(
            "缺 resizeableActivity=true 时 PiP 窗口无法改尺寸",
            "true",
            mainActivity.androidAttribute("resizeableActivity"),
        )
        val configChanges = mainActivity.androidAttribute("configChanges").orEmpty()
        listOf("orientation", "screenSize", "screenLayout", "smallestScreenSize").forEach { change ->
            assertTrue(
                "configChanges 必须声明 $change，否则 PiP 改窗会重建 Activity 并中断播放",
                configChanges.contains(change),
            )
        }
    }

    private fun manifestFile(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("未找到 app/src/main/AndroidManifest.xml")
    }

    private fun Element.androidAttribute(name: String): String? =
        getAttributeNS("http://schemas.android.com/apk/res/android", name).takeIf { it.isNotEmpty() }
}
