package com.mediahub.player.mpv

import android.content.Context
import android.view.Surface
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MpvHttpBridge
import dev.jdtech.mpv.MPVLib

/** Small native boundary: lifecycle tests exercise the engine without JNI or sockets. */
internal interface MpvBridge {
    fun start(url: String, headers: Map<String, String>): String

    /**
     * 以**单个本地媒体文件**启动回环桥（M9-P0）。
     *
     * 默认实现**明确不支持**：实现方必须显式提供本地字节源，
     * 否则本地播放会被静默路由到 HTTP 上游（真机实测表现为黑屏、无 time-pos）。
     */
    fun startLocalFile(path: String): String =
        throw UnsupportedOperationException("MpvBridge 未实现本地文件上游")

    fun startContentUri(uri: String): String =
        throw UnsupportedOperationException("MpvBridge 未实现文档上游")

    fun stop()
}

internal interface MpvInstance {
    enum class Format { DOUBLE, FLAG, STRING }
    enum class Event { FILE_LOADED, VIDEO_RECONFIG, AUDIO_RECONFIG, END_FILE }
    interface Observer {
        fun property(name: String, value: Boolean)
        fun property(name: String, value: Double)
        fun property(name: String, value: String)
        fun event(event: Event)
    }
    fun addObserver(observer: Observer)
    fun setOptionString(name: String, value: String)
    fun init()
    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun observeProperty(name: String, format: Format)
    fun command(args: Array<String>)
    fun setPropertyBoolean(name: String, value: Boolean)
    fun setPropertyDouble(name: String, value: Double)
    fun getPropertyBoolean(name: String): Boolean?
    fun getPropertyDouble(name: String): Double?
    fun getPropertyString(name: String): String?
    fun destroy()
}

/**
 * 创建 mpv 回环桥（M16-S1 生产观测注入）。
 *
 * [ledger] 为可选观测台账：
 * - 传入时，桥会把**回环下游接入**与**本地文件读取**两类事件上报到该台账
 *   （两者都不是上游出网，单列计数）；
 * - 不传时（旧调用形式）行为与接线前完全一致 —— 默认不观测。
 *
 * 注意：桥到自己 OkHttp 上游的那一次调用由 HttpClientFactory 上的 EventListener 计数
 * （RequestChannel.BRIDGE_UPSTREAM），**不经**本参数，避免与回环计数混为一谈。
 */
internal fun createMpvBridge(
    factory: HttpClientFactory,
    ledger: com.mediahub.core.network.NetworkExchangeLedger? = null,
    context: Context? = null,
): MpvBridge = object : MpvBridge {
    private val delegate = MpvHttpBridge(factory, ledger)
    override fun start(url: String, headers: Map<String, String>) = delegate.start(url, headers)
    override fun startLocalFile(path: String) = delegate.startLocalFile(path)
    override fun startContentUri(uri: String) = delegate.startContent(
        AndroidContentByteSource(checkNotNull(context).contentResolver, android.net.Uri.parse(uri))
    )
    override fun stop() = delegate.stop()
}

internal fun createMpvInstance(context: Context): MpvInstance {
    val delegate = MPVLib.create(context) ?: error("mpv create failed")
    return object : MpvInstance {
        override fun addObserver(observer: MpvInstance.Observer) {
            delegate.addObserver(object : MPVLib.EventObserver {
                override fun eventProperty(property: String) = Unit
                override fun eventProperty(property: String, value: Long) = Unit
                override fun eventProperty(property: String, value: Boolean) = observer.property(property, value)
                override fun eventProperty(property: String, value: Double) = observer.property(property, value)
                override fun eventProperty(property: String, value: String) = observer.property(property, value)
                override fun event(eventId: Int) {
                    val event = when (eventId) {
                        MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> MpvInstance.Event.FILE_LOADED
                        MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> MpvInstance.Event.VIDEO_RECONFIG
                        MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG -> MpvInstance.Event.AUDIO_RECONFIG
                        MPVLib.MpvEvent.MPV_EVENT_END_FILE -> MpvInstance.Event.END_FILE
                        else -> return
                    }
                    observer.event(event)
                }
            })
        }
        override fun setOptionString(name: String, value: String) { delegate.setOptionString(name, value) }
        override fun init() = delegate.init()
        override fun attachSurface(surface: Surface) = delegate.attachSurface(surface)
        override fun detachSurface() = delegate.detachSurface()
        override fun observeProperty(name: String, format: MpvInstance.Format) {
            delegate.observeProperty(name, when (format) {
                MpvInstance.Format.DOUBLE -> MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
                MpvInstance.Format.FLAG -> MPVLib.MpvFormat.MPV_FORMAT_FLAG
                MpvInstance.Format.STRING -> MPVLib.MpvFormat.MPV_FORMAT_STRING
            })
        }
        override fun command(args: Array<String>) { delegate.command(args) }
        override fun setPropertyBoolean(name: String, value: Boolean) { delegate.setPropertyBoolean(name, value) }
        override fun setPropertyDouble(name: String, value: Double) { delegate.setPropertyDouble(name, value) }
        override fun getPropertyBoolean(name: String) = delegate.getPropertyBoolean(name)
        override fun getPropertyDouble(name: String) = delegate.getPropertyDouble(name)
        override fun getPropertyString(name: String) = delegate.getPropertyString(name)
        override fun destroy() = delegate.destroy()
    }
}
