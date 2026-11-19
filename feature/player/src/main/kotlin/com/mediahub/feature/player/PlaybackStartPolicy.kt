package com.mediahub.feature.player

/** Chooses where a player session starts when a route carries an explicit switch position. */
object PlaybackStartPolicy {
    fun startPositionMs(explicitStartMs: Long?, resumeMs: Long?): Long {
        val explicit = explicitStartMs?.takeIf { it >= 0L }
        if (explicit != null) return explicit
        return resumeMs?.takeIf { it >= 0L } ?: 0L
    }
}
