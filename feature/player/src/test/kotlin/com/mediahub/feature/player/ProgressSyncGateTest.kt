package com.mediahub.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSyncGateTest {
    @Test
    fun `periodic ticks are throttled independently for local and remote sinks`() {
        val gate = ProgressSyncGate(localIntervalMs = 5_000L)

        val first = gate.onPeriodic(nowEpochMs = 1_000L, remoteIntervalMs = 10_000L)
        assertTrue(first.saveLocal)
        assertTrue(first.reportRemote)

        val oneSecondLater = gate.onPeriodic(nowEpochMs = 2_000L, remoteIntervalMs = 10_000L)
        assertFalse(oneSecondLater.saveLocal)
        assertFalse(oneSecondLater.reportRemote)

        val localDue = gate.onPeriodic(nowEpochMs = 6_000L, remoteIntervalMs = 10_000L)
        assertTrue(localDue.saveLocal)
        assertFalse(localDue.reportRemote)

        val remoteDue = gate.onPeriodic(nowEpochMs = 11_000L, remoteIntervalMs = 10_000L)
        assertTrue(remoteDue.saveLocal)
        assertTrue(remoteDue.reportRemote)
    }

    @Test
    fun `critical event flushes immediately and resets periodic windows`() {
        val gate = ProgressSyncGate(localIntervalMs = 5_000L)
        gate.onPeriodic(1_000L, 10_000L)

        val critical = gate.onCritical(2_000L)
        assertTrue(critical.saveLocal)
        assertTrue(critical.reportRemote)

        val afterCritical = gate.onPeriodic(3_000L, 10_000L)
        assertFalse(afterCritical.saveLocal)
        assertFalse(afterCritical.reportRemote)
    }
}
