package com.mediahub.feature.detail.version

import com.mediahub.model.HdrType
import com.mediahub.model.MediaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多版本选择纯函数：
 * - 面板显隐（>=2 个版本才显示）；
 * - 默认选中服务端首项（服务端顺序即优先级，不重排）；
 * - 指定 id 缺失时不得虚标任何一项（防"看起来选中了但其实没选"）；
 * - 标签/副标签格式化与空值兜底。
 */
class VersionSelectionTest {

    private fun version(
        id: String,
        name: String = "版本$id",
        qualityLabel: String? = null,
        container: String? = "mkv",
        width: Int? = null,
        height: Int? = null,
        hdrType: HdrType = HdrType.NONE,
        bitrate: Long? = null,
        sizeBytes: Long? = null,
    ) = MediaVersion(
        id = id,
        name = name,
        qualityLabel = qualityLabel,
        container = container,
        width = width,
        height = height,
        hdrType = hdrType,
        bitrate = bitrate,
        sizeBytes = sizeBytes,
    )

    @Test
    fun `panel is hidden for empty or single version`() {
        assertFalse(shouldShowVersionPanel(emptyList()))
        assertFalse(shouldShowVersionPanel(listOf(version("a"))))
    }

    @Test
    fun `panel is visible for two versions`() {
        assertTrue(shouldShowVersionPanel(listOf(version("a"), version("b"))))
    }

    @Test
    fun `default selection is first version in server order`() {
        val rows = versionRows(
            listOf(version("4k"), version("1080p"), version("720p")),
            selectedId = null,
        )
        assertEquals(listOf("4k", "1080p", "720p"), rows.map { it.id })
        assertTrue("服务端首项必须默认选中", rows[0].isSelected)
        assertEquals(1, rows.count { it.isSelected })
    }

    @Test
    fun `explicit selection wins over server order`() {
        val rows = versionRows(listOf(version("4k"), version("1080p")), selectedId = "1080p")
        assertFalse(rows[0].isSelected)
        assertTrue(rows[1].isSelected)
    }

    @Test
    fun `unknown selection marks nothing selected`() {
        val rows = versionRows(listOf(version("4k"), version("1080p")), selectedId = "gone")
        assertEquals(0, rows.count { it.isSelected })
    }

    @Test
    fun `label falls back from quality to name to positional default`() {
        val rows = versionRows(
            listOf(
                version("a", name = "主版本", qualityLabel = "杜比视界"),
                version("b", name = "HDR 版", qualityLabel = null),
                version("c", name = "", qualityLabel = "  "),
            ),
            selectedId = null,
        )
        assertEquals("杜比视界", rows[0].label)
        assertEquals("HDR 版", rows[1].label)
        assertEquals("版本 3", rows[2].label)
    }

    @Test
    fun `details join resolution hdr bitrate and size`() {
        val rows = versionRows(
            listOf(
                version(
                    id = "uhd",
                    width = 3840,
                    height = 2160,
                    hdrType = HdrType.DOLBY_VISION,
                    bitrate = 25_000_000L,
                    sizeBytes = 20_000_000_000L,
                )
            ),
            selectedId = null,
        )
        assertEquals("3840×2160 · 杜比视界 · 25.0 Mbps · 20.0 GB", rows.single().detail)
    }

    @Test
    fun `details are null when no technical metadata is available`() {
        val rows = versionRows(listOf(version("plain")), selectedId = null)
        assertNull(rows.single().detail)
    }

    @Test
    fun `partial metadata omits missing pieces`() {
        val rows = versionRows(
            listOf(version("hdr", height = 1080, hdrType = HdrType.HDR10)),
            selectedId = null,
        )
        assertEquals("1080 · HDR10", rows.single().detail)
    }
}
