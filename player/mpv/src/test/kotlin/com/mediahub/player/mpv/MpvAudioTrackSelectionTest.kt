package com.mediahub.player.mpv

import com.mediahub.core.logging.Logger
import com.mediahub.model.PlaybackSource
import com.mediahub.player.engine.PlaybackSession
import com.mediahub.player.engine.TrackSelection
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V16-A：mpv **生产引擎**的音轨枚举与选轨行为（不是复制 mapper 的替身测试）。
 *
 * 用真实 [MpvPlaybackEngine] + fake runtime，只要引擎不调 fake 就打不出 aid 写入，
 * 因此下列断言能真正杀死“把 selectAudioTrack 改回 Unit”这类回归。
 */
class MpvAudioTrackSelectionTest {

    private object NoLogger : Logger {
        override fun d(tag: com.mediahub.core.logging.LogTag, message: String) = Unit
        override fun i(tag: com.mediahub.core.logging.LogTag, message: String) = Unit
        override fun w(tag: com.mediahub.core.logging.LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: com.mediahub.core.logging.LogTag, message: String, throwable: Throwable?) = Unit
    }

    private class FakeBridge : MpvBridge {
        override fun start(url: String, headers: Map<String, String>) = "http://127.0.0.1/audio"
        override fun stop() = Unit
    }

    /** 可控 track-list 与命令记录的 fake mpv。 */
    private class FakeInstance : MpvInstance {
        var observer: MpvInstance.Observer? = null
        var tracks: List<Map<String, String?>> = emptyList()
        var currentAid: String = "no"
        val aidWrites = mutableListOf<String>()

        override fun addObserver(observer: MpvInstance.Observer) { this.observer = observer }
        override fun setOptionString(name: String, value: String) = Unit
        override fun init() = Unit
        override fun attachSurface(surface: Surface) = Unit
        override fun detachSurface() = Unit
        override fun observeProperty(name: String, format: MpvInstance.Format) = Unit
        override fun command(args: Array<String>) {
            if (args.size == 3 && args[0] == "set" && args[1] == "aid") {
                aidWrites += args[2]
                currentAid = args[2]
                // 模拟 native 真实回写 selected 标记
                tracks = tracks.map { row ->
                    if (row["type"] == "audio") row.toMutableMap().apply {
                        this["selected"] = if (row["id"] == args[2]) "yes" else "no"
                    } else row
                }
            }
        }
        override fun setPropertyBoolean(name: String, value: Boolean) = Unit
        override fun setPropertyDouble(name: String, value: Double) = Unit
        override fun getPropertyBoolean(name: String): Boolean? = null
        override fun getPropertyDouble(name: String): Double? =
            if (name == "track-list/count") tracks.size.toDouble() else null
        override fun getPropertyString(name: String): String? {
            if (name == "aid") return currentAid
            val prefix = "track-list/"
            if (!name.startsWith(prefix)) return null
            val rest = name.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val index = rest.substring(0, slash).toIntOrNull() ?: return null
            val prop = rest.substring(slash + 1)
            return tracks.getOrNull(index)?.get(prop)
        }
        override fun destroy() = Unit
    }

    private fun audioTrack(id: String, lang: String, selected: Boolean) = mapOf(
        "id" to id,
        "type" to "audio",
        "lang" to lang,
        "codec" to "aac",
        "selected" to if (selected) "yes" else "no",
    )

    private fun videoTrack() = mapOf("id" to "1", "type" to "video", "selected" to "yes")

    private fun session(itemId: String = "v16-a") = PlaybackSession(
        serverId = "srv-v16",
        itemId = itemId,
        itemTitle = "v16 fixture",
        source = PlaybackSource(url = "https://media.example/v16.mkv", mimeType = "video/x-matroska"),
    )

    private fun engineWith(instance: FakeInstance, scope: CoroutineScope) = MpvPlaybackEngine(
        logger = NoLogger,
        scope = scope,
        bridgeFactory = { FakeBridge() },
        instanceFactory = { instance },
        elapsedRealtime = { 100L },
        currentTimeMillis = { 200L },
        deferNative = { it() },
    )

    @Test
    fun `file loaded publishes two mapped audio tracks with ui indices zero and one`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val instance = FakeInstance().apply {
            tracks = listOf(videoTrack(), audioTrack("1", "eng", true), audioTrack("2", "chi", false))
        }
        val engine = engineWith(instance, scope)
        try {
            engine.play(session())
            withTimeout(5_000) { while (instance.observer == null) delay(10) }
            instance.observer!!.event(MpvInstance.Event.FILE_LOADED)
            withTimeout(2_000) { while (engine.uiState.value.audioTracks.size < 2) delay(10) }

            val tracks = engine.uiState.value.audioTracks
            assertEquals("双音轨必须发布 2 条", 2, tracks.size)
            assertEquals("UI 序号必须是同类型内 0..N-1", listOf(0, 1), tracks.map { it.index })
            assertEquals(listOf("eng", "chi"), tracks.map { it.language })
            assertEquals("首轨选中", true, tracks[0].isSelected)
            assertEquals("次轨未选中", false, tracks[1].isSelected)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `selecting ui index one issues native aid for the second track and re reads selection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val instance = FakeInstance().apply {
            tracks = listOf(videoTrack(), audioTrack("1", "eng", true), audioTrack("2", "chi", false))
        }
        val engine = engineWith(instance, scope)
        try {
            engine.play(session())
            withTimeout(5_000) { while (instance.observer == null) delay(10) }
            instance.observer!!.event(MpvInstance.Event.FILE_LOADED)
            withTimeout(2_000) { while (engine.uiState.value.audioTracks.size < 2) delay(10) }

            // UI index 1 → 必须是 mpv 的 track id "2"（不是 stream index、不是列表位置）
            engine.selectAudioTrack(TrackSelection(1, 0))
            assertEquals("必须下发 set aid 2", listOf("2"), instance.aidWrites)
            withTimeout(2_000) {
                while (engine.uiState.value.audioTracks.getOrNull(1)?.isSelected != true) delay(10)
            }
            assertEquals("选中态以 native 回读为准", true, engine.uiState.value.audioTracks[1].isSelected)
            assertEquals(false, engine.uiState.value.audioTracks[0].isSelected)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `an out of range ui index must not issue any aid command`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val instance = FakeInstance().apply {
            tracks = listOf(videoTrack(), audioTrack("1", "eng", true), audioTrack("2", "chi", false))
        }
        val engine = engineWith(instance, scope)
        try {
            engine.play(session())
            withTimeout(5_000) { while (instance.observer == null) delay(10) }
            instance.observer!!.event(MpvInstance.Event.FILE_LOADED)
            withTimeout(2_000) { while (engine.uiState.value.audioTracks.size < 2) delay(10) }

            engine.selectAudioTrack(TrackSelection(9, 0))
            assertTrue("无效序号不得下发任何命令（保持现状，不伪造成功）", instance.aidWrites.isEmpty())
            assertEquals("选中态不得被乐观改写", true, engine.uiState.value.audioTracks[0].isSelected)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `off issues aid no instead of index zero`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val instance = FakeInstance().apply {
            tracks = listOf(videoTrack(), audioTrack("1", "eng", true), audioTrack("2", "chi", false))
        }
        val engine = engineWith(instance, scope)
        try {
            engine.play(session())
            withTimeout(5_000) { while (instance.observer == null) delay(10) }
            instance.observer!!.event(MpvInstance.Event.FILE_LOADED)
            withTimeout(2_000) { while (engine.uiState.value.audioTracks.size < 2) delay(10) }

            engine.selectAudioTrack(null)
            assertEquals("关闭音轨必须下发 aid=no，不能等同索引 0", listOf("no"), instance.aidWrites)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `switching source does not reuse the previous source track list`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val instance = FakeInstance().apply {
            tracks = listOf(videoTrack(), audioTrack("3", "eng", true), audioTrack("7", "jpn", false))
        }
        val engine = engineWith(instance, scope)
        try {
            engine.play(session("source-a"))
            withTimeout(5_000) { while (instance.observer == null) delay(10) }
            instance.observer!!.event(MpvInstance.Event.FILE_LOADED)
            withTimeout(2_000) { while (engine.uiState.value.audioTracks.size < 2) delay(10) }
            assertEquals(listOf("eng", "jpn"), engine.uiState.value.audioTracks.map { it.language })

            // 切源：新片源只有 1 条音轨（id=1），旧 source-a 的 3/7 不得残留
            instance.tracks = listOf(videoTrack(), audioTrack("1", "chi", true))
            engine.play(session("source-b"))
            withTimeout(5_000) { while (instance.observer == null) delay(10) }
            withTimeout(2_000) {
                // 切源后必须清空旧列表（在新 FILE_LOADED 之前不得沿用旧轨道）
                while (engine.uiState.value.audioTracks.map { it.language }.contains("jpn")) delay(10)
            }
            instance.observer!!.event(MpvInstance.Event.FILE_LOADED)
            withTimeout(2_000) { while (engine.uiState.value.audioTracks.size != 1) delay(10) }
            assertEquals(
                "切源后只允许新片源的 1 条音轨",
                listOf("chi"),
                engine.uiState.value.audioTracks.map { it.language },
            )
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
