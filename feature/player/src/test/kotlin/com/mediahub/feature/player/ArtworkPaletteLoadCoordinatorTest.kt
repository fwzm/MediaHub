package com.mediahub.feature.player

import com.mediahub.core.ui.effects.VisualPalette
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkPaletteLoadCoordinatorTest {

    @Test
    fun `successful extraction is cached by stable key`() = runTest {
        var calls = 0
        val expected = palette(0xFF113355.toInt())
        val coordinator = ArtworkPaletteLoadCoordinator(maxEntries = 2) {
            calls += 1
            expected
        }

        assertSame(expected, coordinator.load("server:item:url", "https://image/1"))
        assertSame(expected, coordinator.load("server:item:url", "https://image/1"))
        assertSame(expected, coordinator.cached("server:item:url"))
        assertEquals(1, calls)
    }

    @Test
    fun `concurrent equal keys share one extraction`() = runTest {
        var calls = 0
        val gate = CompletableDeferred<VisualPalette?>()
        val coordinator = ArtworkPaletteLoadCoordinator {
            calls += 1
            gate.await()
        }

        val first = async { coordinator.load("same", "https://image/same") }
        runCurrent()
        val second = async { coordinator.load("same", "https://image/same") }
        runCurrent()
        assertEquals(1, calls)

        val expected = palette(0xFF446688.toInt())
        gate.complete(expected)
        assertSame(expected, first.await())
        assertSame(expected, second.await())
        assertEquals(1, calls)
    }

    @Test
    fun `failure is fail soft and is not cached`() = runTest {
        var calls = 0
        val coordinator = ArtworkPaletteLoadCoordinator {
            calls += 1
            error("decode failed")
        }

        assertNull(coordinator.load("key", "https://image/bad"))
        assertNull(coordinator.load("key", "https://image/bad"))
        assertNull(coordinator.cached("key"))
        assertEquals(2, calls)
    }

    @Test
    fun `least recently used entry is evicted at capacity`() = runTest {
        var calls = 0
        val coordinator = ArtworkPaletteLoadCoordinator(maxEntries = 2) { url ->
            calls += 1
            palette(url.hashCode() or 0xFF000000.toInt())
        }

        coordinator.load("one", "one")
        coordinator.load("two", "two")
        coordinator.load("one", "one") // refresh one
        coordinator.load("three", "three") // evicts two
        coordinator.load("two", "two") // reload

        assertEquals(4, calls)
    }

    private fun palette(primary: Int) = VisualPalette(
        background = 0xFF080A10.toInt(),
        primary = primary,
        secondary = 0xFF334455.toInt(),
        accent = 0xFFCCDDEE.toInt(),
    )
}
