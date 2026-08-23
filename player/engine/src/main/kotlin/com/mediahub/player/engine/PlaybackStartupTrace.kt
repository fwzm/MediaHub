package com.mediahub.player.engine

import android.os.SystemClock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 单次播放会话级起播全链路追踪（U4-A）。
 *
 * 一条 [PlaybackSession] 对应一条 trace；由 PlayerViewModel 创建并沿链路传递。
 * 线程安全：milestone 用 ConcurrentHashMap.putIfAbsent 保证只记第一次。
 * 所有耗时基于 [SystemClock.elapsedRealtime]（monotonic）。
 *
 * 不伪造数据：拿不到可靠信号的 milestone 保持 null，不填默认值。
 */
class PlaybackStartupTrace(
    val traceId: String,
    val serverId: String,
    val itemId: String,
    /** 用户选择的引擎模式（AUTO/MEDIA3/MPV）。 */
    val requestedEngineMode: String,
    /** 可注入时钟（默认 SystemClock；JVM 测试传 { 0L } 或递增序列）。 */
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    enum class Milestone {
        PLAY_REQUESTED,
        DETAIL_SNAPSHOT_READY,
        PLAYBACK_INFO_REQUEST_STARTED,
        PLAYBACK_INFO_RESPONSE_RECEIVED,
        SOURCE_RESOLVED,
        ENGINE_SELECTION_STARTED,
        ENGINE_SELECTED,
        MEDIA_REQUEST_STARTED,
        MEDIA_FIRST_BYTE,
        ENGINE_PREPARE_STARTED,
        ENGINE_READY,
        VIDEO_DECODER_INITIALIZED,
        AUDIO_INPUT_FORMAT_SEEN,
        AUDIO_DECODER_INITIALIZED,
        FIRST_FRAME_RENDERED,
        PLAYING,
        FAILED,
        // mpv-specific
        MPV_BRIDGE_START,
        MPV_INSTANCE_CREATE_STARTED,
        MPV_INSTANCE_CREATED,
        MPV_INIT_STARTED,
        MPV_INIT_FINISHED,
        MPV_LOADFILE,
        MPV_FILE_LOADED,
        MPV_VIDEO_RECONFIG,
        MPV_AUDIO_RECONFIG,
    }

    private val startedElapsedMs = clock()
    private val _milestones = ConcurrentHashMap<Milestone, Long>()
    private val _metadata = ConcurrentHashMap<String, String>()

    /** 记录 milestone（只记第一次，重复调用不覆盖）。 */
    fun record(milestone: Milestone) {
        _milestones.putIfAbsent(milestone, clock())
    }

    /** 记录 milestone 并附带元数据（如 engineKind、reason、codec 等）。 */
    fun record(milestone: Milestone, metadata: Map<String, String>) {
        _milestones.putIfAbsent(milestone, clock())
        _metadata.putAll(metadata)
    }

    fun milestoneElapsedMs(milestone: Milestone): Long? = _milestones[milestone]?.let { it - startedElapsedMs }

    fun metadata(key: String): String? = _metadata[key]

    fun putMetadata(key: String, value: String) { _metadata[key] = value }

    fun snapshot(): Map<Milestone, Long> = _milestones.toMap()

    /** 距 trace 起点的已过时间。 */
    fun elapsedMs(): Long = clock() - startedElapsedMs

    /**
     * 结构化摘要（单行 logcat 友好，不含敏感 URL/header/token）。
     */
    fun summary(): String {
        val sb = StringBuilder("StartupTrace")
        sb.append(" traceId=").append(traceId)
        sb.append(" itemId=").append(itemId)
        sb.append(" mode=").append(requestedEngineMode)
        metadata("engine")?.let { sb.append(" engine=").append(it) }
        metadata("signature")?.let { sb.append(" signature=").append(it) }

        fun dur(label: String, from: Milestone?, to: Milestone?) {
            val f = from?.let { _milestones[it] } ?: return
            val t = to?.let { _milestones[it] } ?: return
            sb.append(' ').append(label).append('=').append(t - f).append("ms")
        }
        fun at(label: String, m: Milestone) {
            _milestones[m]?.let { sb.append(' ').append(label).append('=').append(it - startedElapsedMs).append("ms") }
        }

        at("detailSnapshot", Milestone.DETAIL_SNAPSHOT_READY)
        dur("playbackInfo", Milestone.PLAYBACK_INFO_REQUEST_STARTED, Milestone.PLAYBACK_INFO_RESPONSE_RECEIVED)
        dur("sourceResolve", Milestone.DETAIL_SNAPSHOT_READY, Milestone.SOURCE_RESOLVED)
        dur("engineSelect", Milestone.ENGINE_SELECTION_STARTED, Milestone.ENGINE_SELECTED)
        at("mediaFirstByte", Milestone.MEDIA_FIRST_BYTE)
        dur("enginePrepare", Milestone.ENGINE_PREPARE_STARTED, Milestone.ENGINE_READY)
        dur("mpvBridge", Milestone.MPV_BRIDGE_START, Milestone.MPV_INSTANCE_CREATED)
        dur("mpvInit", Milestone.MPV_INSTANCE_CREATED, Milestone.MPV_INIT_FINISHED)
        dur("mpvFileLoaded", Milestone.MPV_LOADFILE, Milestone.MPV_FILE_LOADED)
        at("firstFrame", Milestone.FIRST_FRAME_RENDERED)
        at("audioInputSeen", Milestone.AUDIO_INPUT_FORMAT_SEEN)
        at("audioDecoderInit", Milestone.AUDIO_DECODER_INITIALIZED)
        at("playing", Milestone.PLAYING)
        metadata("mediaFirstByteMs")?.let { sb.append(' ').append(it) } // legacy key
        _milestones[Milestone.MEDIA_FIRST_BYTE]?.let { sb.append(" mediaFirstByte=").append(it - startedElapsedMs).append("ms") }
        metadata("mediaProtocol")?.let { sb.append(" mediaProtocol=").append(it) }
        metadata("mediaRedirects")?.let { sb.append(" redirects=").append(it) }
        metadata("mediaCode")?.let { sb.append(" code=").append(it) }
        metadata("mediaAcceptRanges")?.let { sb.append(" ranges=").append(it) }

        if (_milestones.containsKey(Milestone.FAILED)) {
            sb.append(" failedStage=").append(metadata("failedStage") ?: "unknown")
            sb.append(" elapsed=").append(elapsedMs()).append("ms")
            metadata("errorCode")?.let { sb.append(" errorCode=").append(it) }
        } else {
            _milestones[Milestone.FIRST_FRAME_RENDERED]?.let { ff ->
                sb.append(" totalTTFF=").append(ff - startedElapsedMs).append("ms")
            }
        }
        return sb.toString()
    }

    /** 转发为 core/network 的 sink 接口（HTTP EventListener 回调 → milestone）。 */
    fun asSink(): com.mediahub.core.network.PlaybackNetworkTraceSink = object : com.mediahub.core.network.PlaybackNetworkTraceSink {
        override fun onPlaybackInfoStart() = record(PlaybackStartupTrace.Milestone.PLAYBACK_INFO_REQUEST_STARTED)
        override fun onPlaybackInfoEnd() = record(PlaybackStartupTrace.Milestone.PLAYBACK_INFO_RESPONSE_RECEIVED)
        override fun onMediaRequestStart() = record(PlaybackStartupTrace.Milestone.MEDIA_REQUEST_STARTED)
        override fun onMediaFirstByte() = record(PlaybackStartupTrace.Milestone.MEDIA_FIRST_BYTE)
        override fun onMediaResponseMetadata(
            code: Int,
            protocol: String,
            redirectCount: Int,
            acceptsRanges: Boolean,
            contentLengthBytes: Long,
            contentRange: String?,
        ) {
            putMetadata("mediaCode", code.toString())
            putMetadata("mediaProtocol", protocol)
            putMetadata("mediaRedirects", redirectCount.toString())
            putMetadata("mediaAcceptRanges", acceptsRanges.toString())
            if (contentLengthBytes > 0) putMetadata("mediaContentLength", contentLengthBytes.toString())
            contentRange?.let { putMetadata("mediaContentRange", it.substringBefore("/")) }
        }
    }

    companion object {
        private val counter = AtomicLong(0)

        fun newTraceId(clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }): String = "trace-${clock()}-${counter.incrementAndGet()}"
    }
}
