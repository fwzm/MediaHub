package com.mediahub.player.mpv

import android.content.Context
import android.view.Surface
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MpvHttpBridge
import dev.jdtech.mpv.MPVLib

/** Small native boundary: lifecycle tests exercise the engine without JNI or sockets. */
internal interface MpvBridge {
    fun start(url: String, headers: Map<String, String>): String
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
    fun destroy()
}

internal fun createMpvBridge(factory: HttpClientFactory): MpvBridge = object : MpvBridge {
    private val delegate = MpvHttpBridge(factory)
    override fun start(url: String, headers: Map<String, String>) = delegate.start(url, headers)
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
        override fun destroy() = delegate.destroy()
    }
}
