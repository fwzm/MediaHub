package com.mediahub.player.mpv

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MpvSurfaceBindingTest {
    private class Native : MpvInstance {
        val calls = mutableListOf<String>()
        var paused = true
        var position = 42.5
        override fun addObserver(observer: MpvInstance.Observer) = Unit
        override fun setOptionString(name: String, value: String) { calls += "option:$name=$value" }
        override fun init() = Unit
        override fun attachSurface(surface: Surface) { calls += "attach" }
        override fun detachSurface() { calls += "detach" }
        override fun observeProperty(name: String, format: MpvInstance.Format) = Unit
        override fun command(args: Array<String>) { calls += args.joinToString(" ") }
        override fun setPropertyBoolean(name: String, value: Boolean) { calls += "$name=$value"; if (name == "pause") paused = value }
        override fun setPropertyDouble(name: String, value: Double) { calls += "$name=$value"; if (name == "time-pos") position = value }
        override fun getPropertyBoolean(name: String): Boolean? = if (name == "pause") paused else null
        override fun getPropertyDouble(name: String): Double? = if (name == "time-pos") position else null
        override fun getPropertyString(name: String): String? = null
        override fun destroy() = Unit
    }
    private fun withSurfaces(test: (Surface, Surface) -> Unit) {
        val textureA = SurfaceTexture(0); val textureB = SurfaceTexture(0)
        val a = Surface(textureA); val b = Surface(textureB)
        try { test(a, b) } finally { a.release(); b.release(); textureA.release(); textureB.release() }
    }
    @Test fun `detach disables video before releasing the JNI surface reference`() = withSurfaces { a, _ ->
        val native = Native(); val binding = MpvSurfaceBinding(native)
        binding.replace(a); native.calls.clear(); binding.replace(null)
        assertEquals(listOf("set vo null", "set force-window no", "detach"), native.calls)
        assertTrue(native.paused)
        assertEquals(42.5, native.position, 0.0)
    }
    @Test fun `initially absent surface disables video without releasing an unowned reference`() {
        val native = Native(); val binding = MpvSurfaceBinding(native)
        binding.replace(null)
        assertEquals(listOf("set vo null", "set force-window no"), native.calls)
        native.calls.clear(); binding.replace(null)
        assertTrue(native.calls.isEmpty())
    }
    @Test fun `reattach rebuilds video on the new surface without playback or seek commands`() = withSurfaces { a, b ->
        val native = Native(); val binding = MpvSurfaceBinding(native)
        binding.replace(a); binding.replace(null); native.calls.clear(); binding.replace(b)
        assertEquals(listOf("attach", "set force-window yes", "set vo gpu"), native.calls)
        assertTrue(native.paused)
        assertEquals(42.5, native.position, 0.0)
    }
    @Test fun `same surface and repeated detach are idempotent`() = withSurfaces { a, _ ->
        val native = Native(); val binding = MpvSurfaceBinding(native)
        binding.replace(a); native.calls.clear(); binding.replace(a)
        assertTrue("Duplicate JNI attach leaks the previous global surface reference", native.calls.isEmpty())
        binding.replace(null); native.calls.clear(); binding.replace(null)
        assertTrue(native.calls.isEmpty())
    }
    @Test fun `direct replacement releases the old surface before attaching the new one`() = withSurfaces { a, b ->
        val native = Native(); val binding = MpvSurfaceBinding(native)
        binding.replace(a); native.calls.clear(); binding.replace(b)
        assertEquals(listOf("set vo null", "set force-window no", "detach", "attach",
            "set force-window yes", "set vo gpu"), native.calls)
    }
}
