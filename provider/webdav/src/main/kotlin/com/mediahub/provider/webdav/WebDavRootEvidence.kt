package com.mediahub.provider.webdav

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Validates the configured Depth:0 resource, preserving path case and reserved escapes. No I/O. */
internal object WebDavRootEvidence {
    fun isRepresented(rootUrl: String, resources: List<WebDavResource>): Boolean {
        val expected = rootUrl.toHttpUrlOrNull() ?: return false
        return resources.any { resource ->
            if (!resource.hasSuccessfulStatus) return@any false
            val resolved = WebDavUrls.resolveSameOrigin(rootUrl, resource.href) ?: return@any false
            val actual = resolved.toHttpUrlOrNull() ?: return@any false
            canonicalPath(actual.encodedPath) == canonicalPath(expected.encodedPath) &&
                actual.encodedQuery == expected.encodedQuery
        }
    }

    private fun canonicalPath(path: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < path.length) {
            if (path[index] == '%' && index + 2 < path.length) {
                val high = path[index + 1].digitToIntOrNull(16)
                val low = path[index + 2].digitToIntOrNull(16)
                if (high != null && low != null) {
                    val value = (high * 16 + low).toChar()
                    if (value in 'a'..'z' || value in 'A'..'Z' || value in '0'..'9' || value in "-._~") {
                        out.append(value)
                    } else {
                        out.append('%').append(path[index + 1].uppercaseChar()).append(path[index + 2].uppercaseChar())
                    }
                    index += 3
                    continue
                }
            }
            out.append(path[index++])
        }
        return out.toString().trimEnd('/')
    }
}
