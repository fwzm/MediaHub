package com.mediahub.feature.detail

import androidx.compose.ui.unit.dp
import com.mediahub.model.HdrType
import com.mediahub.model.MediaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailLayoutPolicyTest {

    @Test
    fun `poster width grows with screen width`() {
        assertEquals(92.dp, detailPosterWidthDp(320))
        assertEquals(104.dp, detailPosterWidthDp(411))
        assertEquals(132.dp, detailPosterWidthDp(800))
    }

    @Test
    fun `version line contains quality resolution hdr bitrate and size`() {
        val version = MediaVersion(
            id = "v1",
            name = "4K 原盘",
            qualityLabel = "BluRay",
            width = 3840,
            height = 2160,
            hdrType = HdrType.HDR10,
            bitrate = 45_000_000,
            sizeBytes = 12L * 1_073_741_824,
        )
        val line = formatVersionLine(version)
        assertTrue(line.contains("4K 原盘"))
        assertTrue(line.contains("BluRay"))
        assertTrue(line.contains("3840x2160"))
        assertTrue(line.contains("HDR10"))
        assertTrue(line.contains("45 Mbps"))
        assertTrue(line.contains("12.0 GB"))
    }

    @Test
    fun `version line skips missing optional fields without placeholders`() {
        val line = formatVersionLine(MediaVersion(id = "v2", name = "默认"))
        assertEquals("默认", line)
    }

    @Test
    fun `height only resolution falls back to p suffix`() {
        val line = formatVersionLine(MediaVersion(id = "v3", name = "1080p", height = 1080))
        assertTrue(line.contains("1080P"))
    }

    @Test
    fun `bytes are formatted as GB or MB`() {
        assertEquals("1.0 GB", formatBytes(1_073_741_824))
        assertEquals("512.0 MB", formatBytes(536_870_912))
        assertEquals("0 B", formatBytes(0))
    }
}
