package com.mediahub.provider.webdav

import java.net.URI
import java.net.URISyntaxException

/** RFC4918 §10.1/18.1: a DAV resource advertises class1 on OPTIONS. No network or credential access. */
internal object WebDavCompliance {
    fun advertisesClassOne(values: List<String>): Boolean {
        // Repeated field values form one list. A comma inside a Coded-URL is not a list separator.
        val value = values.joinToString(",")
        var index = 0
        var classOne = false
        while (index < value.length) {
            while (index < value.length && (value[index] == ' ' || value[index] == '\t' || value[index] == ',')) index++
            if (index == value.length) break
            if (value[index] == '<') {
                val end = value.indexOf('>', index + 1)
                if (end < 0 || !absoluteCodedUri(value.substring(index + 1, end))) return false
                index = end + 1
            } else {
                val start = index
                while (index < value.length && tokenCharacter(value[index])) index++
                if (index == start) return false
                if (index - start == 1 && value[start] == '1') classOne = true
            }
            while (index < value.length && (value[index] == ' ' || value[index] == '\t')) index++
            if (index < value.length && value[index] != ',') return false
        }
        return classOne
    }

    private fun absoluteCodedUri(value: String): Boolean {
        if (value.isEmpty() || value.any { it.code !in 0x21..0x7e }) return false
        val uri = try { URI(value) } catch (_: URISyntaxException) { return false }
        return uri.isAbsolute && uri.rawFragment == null
    }

    private fun tokenCharacter(value: Char): Boolean =
        value in 'a'..'z' || value in 'A'..'Z' || value in '0'..'9' ||
            value in "!#\$%&'*+-.^_`|~"
}
