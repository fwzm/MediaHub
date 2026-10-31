package com.mediahub.core.common.sync

import java.security.MessageDigest

/**
 * 记录内容哈希（W6-72）：两端必须算出同一个 64 位小写十六进制串。
 *
 * 权威实现在 Windows 的 SourceSyncContentHash.cs：把记录的四个字段（accountScope / displayName /
 * endpoints / sourceType，字母序）写成一份紧凑 JSON，再取 SHA-256。JSON 的转义规则同样来自
 * Utf8JsonWriter，所以字符串必须经 WireJson.quote，不能直接拼。
 *
 * 与 SourceSyncFingerprints 的区别：那里的自定义分隔串是本机去重用的，算法与 Windows 不同；
 * 基线要与其他设备交换，只能用这里的规范 JSON 算法。
 */
object SourceSyncContentHash {

    /** 参与哈希的字段，顺序与 Windows 的 SourceSyncFields.All 一致（字母序）。 */
    val FIELDS: List<String> = listOf("accountScope", "displayName", "endpoints", "sourceType")

    fun compute(record: SourceRecord): String = hash(canonicalize(record))

    fun computeFields(record: SourceRecord): Map<String, String> {
        val fields = LinkedHashMap<String, String>(FIELDS.size)
        for (field in FIELDS) {
            val writer = WireJsonTextWriter(indented = false)
            writer.startObject()
            writer.name(field)
            writeFieldValue(writer, field, record)
            writer.endObject()
            fields[field] = hash(writer.toString())
        }
        return fields
    }

    fun canonicalize(record: SourceRecord): String {
        val writer = WireJsonTextWriter(indented = false)
        writer.startObject()
        for (field in FIELDS) {
            writer.name(field)
            writeFieldValue(writer, field, record)
        }
        writer.endObject()
        return writer.toString()
    }

    private fun writeFieldValue(writer: WireJsonTextWriter, field: String, record: SourceRecord) {
        when (field) {
            "accountScope" -> {
                val scope = normalizedScope(record.accountScope)
                if (scope == null) writer.nullValue() else writer.value(scope)
            }
            "displayName" -> writer.value(record.displayName)
            "endpoints" -> {
                writer.startArray()
                for (endpoint in canonicalEndpoints(record)) {
                    writer.startObject()
                    writer.name("enabled").value(endpoint.enabled)
                    writer.name("isPrimary").value(endpoint.isPrimary)
                    writer.name("sortOrder").value(endpoint.sortOrder)
                    writer.name("url").value(endpoint.url)
                    writer.endObject()
                }
                writer.endArray()
            }
            "sourceType" -> writer.value(record.sourceType.wire)
            else -> writer.nullValue()
        }
    }

    /**
     * 账号作用域归一：空白视为 null，其余剥空白后只折 ASCII 大小写。
     *
     * 只折 ASCII 是与 Windows 侧一致的取舍（W6-67 的文本口径）：全 Unicode 小写在 .NET 与 JVM 上结果不同
     * （例如 U+0130 在 .NET 保持不变、在 JVM 变两个字符），无法用同一张表抹平。
     */
    internal fun normalizedScope(accountScope: String?): String? {
        if (WireText.isBlank(accountScope)) return null
        return WireText.lowercaseAscii(WireText.trim(accountScope!!))
    }

    private fun normalizedUrl(url: String?): String {
        if (WireText.isBlank(url)) return ""
        return SyncUrlNormalizer.normalize(url) ?: WireText.trim(url!!)
    }

    private data class CanonicalEndpoint(
        val sortOrder: Int,
        val url: String,
        val isPrimary: Boolean,
        val enabled: Boolean,
    )

    private fun canonicalEndpoints(record: SourceRecord): List<CanonicalEndpoint> {
        val endpoints = record.endpoints.map { endpoint ->
            CanonicalEndpoint(
                sortOrder = endpoint.sortOrder,
                url = normalizedUrl(endpoint.url),
                isPrimary = endpoint.isPrimary,
                enabled = endpoint.enabled,
            )
        }
        return endpoints.sortedWith(
            compareBy<CanonicalEndpoint> { it.sortOrder }
                .thenBy { it.url }
                .thenBy { if (it.isPrimary) 0 else 1 }
                .thenBy { if (it.enabled) 0 else 1 },
        )
    }

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX_LOWER[value shr 4])
            builder.append(HEX_LOWER[value and 0xF])
        }
        return builder.toString()
    }

    private const val HEX_LOWER = "0123456789abcdef"
}
