package com.mediahub.core.common.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 基线文档被拒绝时给出的稳定原因码。 */
object SourceSyncBaselineCodecReasons {
    /** 文档不是 JSON，或不是本编解码器写出的那个 JSON 对象。 */
    const val MALFORMED_JSON: String = "malformed-json"
    /** 文档长度超过 [SourceSyncBaselineCodec.MAX_DOCUMENT_CHARS]。 */
    const val TOO_LARGE: String = "baseline-too-large"
    /** 文档嵌套层数超过 [SourceSyncBaselineCodec.MAX_DEPTH]。 */
    const val TOO_DEEP: String = "baseline-too-deep"
}

/**
 * [SourceSyncBaseline] 的 JSON 编解码器（W6-72，对齐 Windows SourceSyncBaselineCodec）。
 *
 * 两条性质比形状更重要：
 * 一、确定性——条目按 syncSourceId 排序、字段哈希按字段名排序，同一份基线永远序列化成同一串字节，
 * 文件存储因此可以比较两份文档而不必做语义 diff，测试也能钉死字节。
 * 二、工作量有界——手改过或恶意的文件在解析前就被长度与嵌套深度挡掉：一个能让应用在加载时崩掉的基线，
 * 比一个当成「不存在」的基线更糟。
 *
 * 可选字段缺失时取中性缺省值（deviceId 与 snapshotId 为 null、修订号 0、缺失的 fieldHashes 为空表——
 * 摘要读作「不知道」，绝不会读成「没变化」）。未知字段忽略并计数，好让更新的写端加字段而不弄坏本读端。
 */
object SourceSyncBaselineCodec {

    /** 本构建写出、也是唯一读取的文档版本。 */
    const val CURRENT_SCHEMA_VERSION: Int = SourceSyncBaseline.CURRENT_VERSION

    /** 单文档条目上限；超过就当敌意文件而不是数据。 */
    const val MAX_ENTRIES: Int = 50000

    /** 单条目字段哈希上限。 */
    const val MAX_FIELD_HASHES: Int = 64

    /** 本读端接受的 JSON 嵌套上限；文档本身从不深过三层。 */
    const val MAX_DEPTH: Int = 16

    /** 文档字符数上限，解析前先查。 */
    const val MAX_DOCUMENT_CHARS: Long = 8L * 1024 * 1024

    // -------------------------------------------------------------- 写出

    /** 把基线序列化成 UTF-8 JSON 文本。 */
    fun serialize(baseline: SourceSyncBaseline, indented: Boolean = true): String =
        serializeToBytes(baseline, indented).toString(Charsets.UTF_8)

    /** 把基线序列化成 UTF-8 字节（无 BOM）。 */
    fun serializeToBytes(baseline: SourceSyncBaseline, indented: Boolean = true): ByteArray {
        val writer = WireJsonTextWriter(indented)
        writeBaseline(writer, baseline)
        return writer.toString().toByteArray(Charsets.UTF_8)
    }

    /** 写一个基线对象；文件存储用它把基线嵌进自己的文档。 */
    internal fun writeBaseline(writer: WireJsonTextWriter, baseline: SourceSyncBaseline) {
        writer.startObject()
        writer.name("version").value(CURRENT_SCHEMA_VERSION)
        writer.name("deviceId")
        val deviceId = baseline.deviceId
        if (deviceId == null) writer.nullValue() else writer.value(deviceId)
        writer.name("snapshotId")
        val snapshotId = baseline.snapshotId
        if (snapshotId == null) writer.nullValue() else writer.value(snapshotId)
        writer.name("entries").startArray()
        for (entry in orderEntries(baseline.entries)) {
            writer.startObject()
            writer.name("syncSourceId").value(entry.syncSourceId)
            writer.name("recordRevision").value(entry.recordRevision)
            writer.name("fieldVersion").value(entry.fieldVersion)
            writer.name("contentHash").value(entry.contentHash)
            writer.name("fieldHashes").startObject()
            for ((field, hash) in orderFields(entry.fieldHashes)) {
                writer.name(field).value(hash)
            }
            writer.endObject()
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
    }

    // -------------------------------------------------------------- 读取

    /** 读一份基线文档；文档坏、太大、太深或版本更新时抛 [SourceSyncContractException]。 */
    fun deserialize(json: String?): SourceSyncBaseline = deserialize(json, IntHolder())

    /** 读一份基线文档，并报告忽略了多少未知字段。 */
    fun deserialize(json: String?, ignoredFieldCount: IntHolder): SourceSyncBaseline {
        val result = tryDeserialize(json)
        if (result is DecodeResult.Failed) {
            throw SourceSyncContractException(
                result.reason,
                "The sync baseline document was rejected with reason " + result.reason + ".",
            )
        }
        val ok = result as DecodeResult.Ok
        ignoredFieldCount.value = ok.ignoredFieldCount
        return ok.baseline
    }

    /** 不抛异常地读一份基线文档。 */
    fun tryDeserialize(json: String?): DecodeResult {
        if (WireText.isBlank(json)) return DecodeResult.Failed(SourceSyncBaselineCodecReasons.MALFORMED_JSON)
        val text = json!!
        if (text.length > MAX_DOCUMENT_CHARS) {
            return DecodeResult.Failed(SourceSyncBaselineCodecReasons.TOO_LARGE)
        }
        if (WireJson.exceedsDepth(text, MAX_DEPTH)) {
            return DecodeResult.Failed(SourceSyncBaselineCodecReasons.TOO_DEEP)
        }
        val root = try {
            Json.parseToJsonElement(text)
        } catch (exception: Exception) {
            return DecodeResult.Failed(SourceSyncBaselineCodecReasons.MALFORMED_JSON)
        }
        if (root !is JsonObject) {
            return DecodeResult.Failed(SourceSyncBaselineCodecReasons.MALFORMED_JSON)
        }
        return readRoot(root)
    }

    /** 读取结果。 */
    sealed interface DecodeResult {
        data class Ok(val baseline: SourceSyncBaseline, val ignoredFieldCount: Int) : DecodeResult
        data class Failed(val reason: String) : DecodeResult
    }

    /** 可变 int 出参（Kotlin 没有 out 参数）。 */
    class IntHolder(var value: Int = 0)

    private fun readRoot(root: JsonObject): DecodeResult {
        var ignored = 0
        val versionElement = root["version"]
            ?: return DecodeResult.Failed("missing-field:version")
        val version = intOrNull(versionElement) ?: return DecodeResult.Failed("invalid-field:version")
        if (version != CURRENT_SCHEMA_VERSION) {
            return DecodeResult.Failed("unsupported-schema-version:" + version.toString())
        }

        var deviceId: String? = null
        var snapshotId: String? = null
        when (val deviceResult = readNullableString(root, "deviceId")) {
            is NullableStringResult.Failed -> return DecodeResult.Failed(deviceResult.reason)
            is NullableStringResult.Ok -> deviceId = deviceResult.value
        }
        when (val snapshotResult = readNullableString(root, "snapshotId")) {
            is NullableStringResult.Failed -> return DecodeResult.Failed(snapshotResult.reason)
            is NullableStringResult.Ok -> snapshotId = snapshotResult.value
        }

        val entries = ArrayList<SourceSyncBaselineEntry>()
        val index = HashMap<String, Int>()
        val entriesElement = root["entries"]
        if (entriesElement != null && entriesElement !is JsonNull) {
            if (entriesElement !is JsonArray) return DecodeResult.Failed("invalid-field:entries")
            if (entriesElement.size > MAX_ENTRIES) {
                return DecodeResult.Failed(SourceSyncBaselineCodecReasons.TOO_LARGE)
            }
            for (element in entriesElement) {
                val read = readEntry(element)
                val entry: SourceSyncBaselineEntry
                when (read) {
                    is EntryResult.Failed -> return DecodeResult.Failed(read.reason)
                    is EntryResult.Ok -> {
                        ignored += read.ignoredFieldCount
                        entry = read.entry
                    }
                }
                // 后者胜：与配对设备文档一致，手改出的重复项不能让结果依赖遍历顺序。
                val key = entry.syncSourceId.lowercaseKey()
                val existingAt = index[key]
                if (existingAt != null) {
                    entries[existingAt] = entry
                } else {
                    index[key] = entries.size
                    entries.add(entry)
                }
            }
        }

        for (name in root.keys) {
            if (!isKnownRootField(name)) ignored += 1
        }

        return DecodeResult.Ok(
            SourceSyncBaseline(
                version = CURRENT_SCHEMA_VERSION,
                deviceId = deviceId,
                snapshotId = snapshotId,
                entries = entries,
            ),
            ignored,
        )
    }

    private sealed interface EntryResult {
        data class Ok(val entry: SourceSyncBaselineEntry, val ignoredFieldCount: Int) : EntryResult
        data class Failed(val reason: String) : EntryResult
    }

    private fun readEntry(element: JsonElement): EntryResult {
        if (element !is JsonObject) return EntryResult.Failed("invalid-field:entries")
        val idElement = element["syncSourceId"]
        if (idElement == null || idElement is JsonNull || idElement !is JsonPrimitive || !idElement.isString) {
            return EntryResult.Failed("invalid-field:syncSourceId")
        }
        val id = idElement.content
        if (WireText.isBlank(id)) return EntryResult.Failed("invalid-field:syncSourceId")

        val recordRevision: Long
        when (val read = readInt64(element, "recordRevision")) {
            is Int64Result.Failed -> return EntryResult.Failed(read.reason)
            is Int64Result.Ok -> recordRevision = read.value
        }
        val fieldVersion: Int
        when (val read = readInt32(element, "fieldVersion")) {
            is Int32Result.Failed -> return EntryResult.Failed(read.reason)
            is Int32Result.Ok -> fieldVersion = read.value
        }

        var contentHash = ""
        val hashElement = element["contentHash"]
        if (hashElement != null && hashElement !is JsonNull) {
            if (hashElement !is JsonPrimitive || !hashElement.isString) {
                return EntryResult.Failed("invalid-field:contentHash")
            }
            contentHash = hashElement.content
        }

        val hashes = LinkedHashMap<String, String>()
        val hashesElement = element["fieldHashes"]
        if (hashesElement != null && hashesElement !is JsonNull) {
            if (hashesElement !is JsonObject) {
                return EntryResult.Failed("invalid-field:fieldHashes")
            }
            if (hashesElement.size > MAX_FIELD_HASHES) {
                return EntryResult.Failed(SourceSyncBaselineCodecReasons.TOO_LARGE)
            }
            for ((field, value) in hashesElement) {
                if (value is JsonNull || value !is JsonPrimitive || !value.isString) {
                    return EntryResult.Failed("invalid-field:fieldHashes")
                }
                hashes[field] = value.content
            }
        }

        var ignored = 0
        for (name in element.keys) {
            if (!isKnownEntryField(name)) ignored += 1
        }

        return EntryResult.Ok(
            SourceSyncBaselineEntry(
                syncSourceId = WireText.trim(id),
                recordRevision = recordRevision,
                fieldVersion = fieldVersion,
                contentHash = contentHash,
                fieldHashes = hashes,
            ),
            ignored,
        )
    }

    private sealed interface NullableStringResult {
        data class Ok(val value: String?) : NullableStringResult
        data class Failed(val reason: String) : NullableStringResult
    }

    private fun readNullableString(parent: JsonObject, name: String): NullableStringResult {
        val element = parent[name] ?: return NullableStringResult.Ok(null)
        if (element is JsonNull) return NullableStringResult.Ok(null)
        if (element !is JsonPrimitive || !element.isString) {
            return NullableStringResult.Failed("invalid-field:" + name)
        }
        return NullableStringResult.Ok(element.content)
    }

    private sealed interface Int64Result {
        data class Ok(val value: Long) : Int64Result
        data class Failed(val reason: String) : Int64Result
    }

    private fun readInt64(parent: JsonObject, name: String): Int64Result {
        val element = parent[name] ?: return Int64Result.Ok(0L)
        if (element is JsonNull) return Int64Result.Ok(0L)
        val value = numericOrNull(element) ?: return Int64Result.Failed("invalid-field:" + name)
        val parsed = value.toLongOrNull() ?: return Int64Result.Failed("invalid-field:" + name)
        return Int64Result.Ok(parsed)
    }

    private sealed interface Int32Result {
        data class Ok(val value: Int) : Int32Result
        data class Failed(val reason: String) : Int32Result
    }

    private fun readInt32(parent: JsonObject, name: String): Int32Result {
        val element = parent[name] ?: return Int32Result.Ok(0)
        if (element is JsonNull) return Int32Result.Ok(0)
        val value = numericOrNull(element) ?: return Int32Result.Failed("invalid-field:" + name)
        val parsed = value.toIntOrNull() ?: return Int32Result.Failed("invalid-field:" + name)
        return Int32Result.Ok(parsed)
    }

    /** 只接受 JSON 数字（不带引号的），返回其字面量文本。 */
    private fun numericOrNull(element: JsonElement): String? {
        if (element !is JsonPrimitive || element.isString) return null
        return element.content
    }

    private fun intOrNull(element: JsonElement?): Int? {
        if (element == null) return null
        return numericOrNull(element)?.toIntOrNull()
    }

    private fun isKnownRootField(name: String): Boolean =
        name == "version" || name == "deviceId" || name == "snapshotId" || name == "entries"

    private fun isKnownEntryField(name: String): Boolean =
        name == "syncSourceId" || name == "recordRevision" || name == "fieldVersion" ||
            name == "contentHash" || name == "fieldHashes"

    private fun orderEntries(entries: List<SourceSyncBaselineEntry>): List<SourceSyncBaselineEntry> =
        entries
            .filter { !WireText.isBlank(it.syncSourceId) }
            .sortedWith(
                compareBy<SourceSyncBaselineEntry> { it.syncSourceId.lowercaseKey() }
                    .thenBy { it.syncSourceId },
            )

    private fun orderFields(hashes: Map<String, String>): List<Pair<String, String>> =
        hashes.entries
            .filter { !WireText.isBlank(it.key) }
            .map { it.key to it.value }
            .sortedWith(compareBy({ it.first }))
}
