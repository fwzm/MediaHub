package com.mediahub.core.database.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProgressRetentionPolicyTest {

    private val dayMs = 24L * 60L * 60L * 1_000L

    @Test
    fun `default retention cuts off ninety days before now`() {
        val now = 1_700_000_000_000L

        assertEquals(now - 90L * dayMs, ProgressRetentionPolicy.cutoffEpochMs(now))
    }

    @Test
    fun `custom retention window is honored`() {
        val now = 1_700_000_000_000L

        assertEquals(now - 7L * dayMs, ProgressRetentionPolicy.cutoffEpochMs(now, retentionDays = 7))
    }

    @Test
    fun `non positive retention window is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProgressRetentionPolicy.cutoffEpochMs(1_700_000_000_000L, retentionDays = 0)
        }
    }
}
