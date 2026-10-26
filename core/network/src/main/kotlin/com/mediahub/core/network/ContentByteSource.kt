package com.mediahub.core.network

import java.io.Closeable
import java.io.InputStream

/** Session-owned local bytes; metadata/opening are invoked only on bridge workers. */
interface ContentByteSource : Closeable {
    data class Info(val length: Long?, val mimeType: String?)
    fun info(): Info
    fun open(): InputStream
}
