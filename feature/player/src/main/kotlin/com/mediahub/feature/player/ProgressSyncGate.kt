package com.mediahub.feature.player

/** 高频位置流的纯逻辑节流门；关键事件会立即刷新两个时间基准。 */
class ProgressSyncGate(
    private val localIntervalMs: Long = 5_000L,
) {
    private var lastLocalAt: Long? = null
    private var lastRemoteAt: Long? = null

    @Synchronized
    fun onPeriodic(nowEpochMs: Long, remoteIntervalMs: Long?): ProgressSyncDecision {
        val saveLocal = isDue(lastLocalAt, nowEpochMs, localIntervalMs)
        val reportRemote = remoteIntervalMs != null && isDue(lastRemoteAt, nowEpochMs, remoteIntervalMs)
        if (saveLocal) lastLocalAt = nowEpochMs
        if (reportRemote) lastRemoteAt = nowEpochMs
        return ProgressSyncDecision(saveLocal, reportRemote)
    }

    @Synchronized
    fun onCritical(nowEpochMs: Long): ProgressSyncDecision {
        lastLocalAt = nowEpochMs
        lastRemoteAt = nowEpochMs
        return ProgressSyncDecision(saveLocal = true, reportRemote = true)
    }

    private fun isDue(lastAt: Long?, now: Long, interval: Long): Boolean =
        lastAt == null || now - lastAt >= interval
}

data class ProgressSyncDecision(
    val saveLocal: Boolean,
    val reportRemote: Boolean,
)
