package com.mediahub.player.mpv

import android.view.Surface

/** Native surface handoff, called only while the engine's native lock is held. */
internal class MpvSurfaceBinding(private val native: MpvInstance) {
    private var surface: Surface? = null
    private var prepared = false

    fun replace(surface: Surface?) {
        if (prepared && this.surface === surface) return
        // The pinned JNI implementation only changes wid. Stop the old video output before
        // its global Surface reference is deleted, then rebuild it against the new window.
        if (this.surface != null || surface == null) {
            native.command(arrayOf("set", "vo", "null"))
            native.command(arrayOf("set", "force-window", "no"))
        }
        if (this.surface != null) {
            native.detachSurface()
            this.surface = null
        }
        if (surface != null) {
            native.attachSurface(surface)
            this.surface = surface
            native.command(arrayOf("set", "force-window", "yes"))
            native.command(arrayOf("set", "vo", "gpu"))
        }
        prepared = true
    }
}
