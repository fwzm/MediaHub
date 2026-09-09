package com.mediahub.core.common

import java.net.URI
import java.util.Locale

/** Comparison of already stored addresses, shared by restore and live authentication state. */
object ServerAddressIdentity {
    fun normalize(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
        val host = uri.host?.lowercase(Locale.ROOT) ?: return trimmed
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val query = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return "${uri.scheme?.lowercase(Locale.ROOT)}://$host$port$path$query"
    }
}
