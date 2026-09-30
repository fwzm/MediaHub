package com.mediahub.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 专业信息偏好默认值契约：产品默认专业模式；未知值占位符固定为 "—"。 */
class ProfessionalInfoPreferencesTest {

    @Test
    fun `product default is expert mode on`() {
        val default = ProfessionalInfoPreferences()

        assertTrue(default.expertMode)
        assertEquals(ProfessionalInfoPreferences.Default, default)
        assertEquals(default, UserPreferences().professionalInfo)
    }

    @Test
    fun `expert mode off keeps data class semantics`() {
        val simplified = ProfessionalInfoPreferences.Default.copy(expertMode = false)

        assertFalse(simplified.expertMode)
        assertEquals(true, simplified.copy(expertMode = true).expertMode)
    }

    @Test
    fun `unknown placeholder is the em dash marker`() {
        assertEquals("—", ProfessionalInfoPreferences.UNKNOWN)
    }
}
