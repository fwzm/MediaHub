package com.mediahub.app.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 审计 §3.2 字号阶：bodySmall / labelSmall / labelMedium 必须在主题中显式定义，
 * 不能依赖 Material3 默认值（默认行高偏紧，中文会挤行）。
 */
class MediaHubTypographyTest {

    @Test
    fun bodySmallUsesAuditScale() {
        val style = MediaHubTypography.bodySmall
        assertEquals(13.sp, style.fontSize)
        assertEquals(19.sp, style.lineHeight)
    }

    @Test
    fun labelSmallUsesAuditScale() {
        val style = MediaHubTypography.labelSmall
        assertEquals(11.sp, style.fontSize)
        assertEquals(14.sp, style.lineHeight)
        assertEquals(0.2.sp, style.letterSpacing)
    }

    @Test
    fun labelMediumUsesAuditScale() {
        val style = MediaHubTypography.labelMedium
        assertEquals(12.sp, style.fontSize)
        assertEquals(16.sp, style.lineHeight)
        assertEquals(FontWeight.Medium, style.fontWeight)
    }
}
