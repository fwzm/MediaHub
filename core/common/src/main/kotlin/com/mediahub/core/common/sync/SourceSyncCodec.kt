package com.mediahub.core.common.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 同步信封的线格式（ADR-S001 D2），与 Windows SourceSyncCodec 逐字段、逐顺序、逐原因码对齐。
 *
 * 为什么手写而不是给 DTO 加 @Serializable：线格式的字段顺序、可选字段的省略规则、以及"哪个属性缺失
 * 该报哪个 reason 码"都是合同的一部分；注解式序列化会把这些交给库的默认行为，读端也就没法保证与
 * Windows 报同一个码。手写约 250 行换来的是两端对同一条坏输入给出同一个 machine reason。
 *
 * 读取策略：未知属性忽略（前向兼容）；必需字段缺失 → missing-field:<名字>；类型不符 → invalid-field:<名字>；
 * 整个文档不是 JSON 对象 → malformed-json。
 */
object SourceSyncCodec {

    /** 读取结果，供不想处理异常的调用方使用（UI 预览、迁移脚本）。 */
    sealed interface DecodeResult {
        data class Ok(val envelope: SourceSyncEnvelope) : DecodeResult
        data class Failed(val reason: String, val message: String?) : DecodeResult
    }

    // ---------------------------------------------------------------- 写出

    fun encode(envelope: SourceSyncEnvelope): String {
        validateForWrite(envelope)
        return buildJsonObject {
            put("schemaVersion", envelope.schemaVersion)
            put("minimumReaderVersion", envelope.minimumReaderVersion)
            put("deviceId", envelope.deviceId)
            put("snapshotId", envelope.snapshotId)
            put("createdAtEpochMs", envelope.createdAtEpochMs)
            putJsonObject("capabilities") {
                put("canSendSecrets", envelope.capabilities.canSendSecrets)
                putJsonArray("sourceTypes") {
                    for (wire in envelope.capabilities.sourceTypes) add(wire)
                }
            }
            putJsonArray("sources") {
                for (record in envelope.sources) add(encodeRecord(record))
            }
            putJsonArray("deletions") {
                for (tombstone in envelope.deletions) add(encodeTombstone(tombstone))
            }
        }.toString()
    }

    private fun encodeRecord(record: SourceRecord): JsonObject = buildJsonObject {
        put("syncSourceId", record.syncSourceId)
        put("sourceType", record.sourceType.raw)
        put("displayName", record.displayName)
        record.accountScope?.let { put("accountScope", it) }
        putJsonArray("endpoints") {
            for (endpoint in record.endpoints) add(encodeEndpoint(endpoint))
        }
        put("recordRevision", record.recordRevision)
        put("fieldVersion", record.fieldVersion)
        // 原样写出：不解释、不重新封装（D6）。本端从不检查其内容。
        record.secrets?.let { put("secrets", it) }
    }

    private fun encodeEndpoint(endpoint: SourceEndpoint): JsonObject = buildJsonObject {
        put("url", endpoint.url)
        put("isPrimary", endpoint.isPrimary)
        put("enabled", endpoint.enabled)
        put("sortOrder", endpoint.sortOrder)
    }

    private fun encodeTombstone(tombstone: SourceTombstone): JsonObject = buildJsonObject {
        put("syncSourceId", tombstone.syncSourceId)
        put("deletedAtEpochMs", tombstone.deletedAtEpochMs)
        put("recordRevision", tombstone.recordRevision)
    }

    /** 写出前的自检：本端绝不生成自己读不回来的文档。 */
    private fun validateForWrite(envelope: SourceSyncEnvelope) {
        if (envelope.schemaVersion != SourceSyncEnvelope.CURRENT_SCHEMA_VERSION) {
            throw SourceSyncContractException.unsupportedSchemaVersion(envelope.schemaVersion)
        }
        if (envelope.minimumReaderVersion < 1) {
            throw SourceSyncContractException.invalidField("minimumReaderVersion", "must be at least 1.")
        }
        if (envelope.minimumReaderVersion > SourceSyncEnvelope.CURRENT_SCHEMA_VERSION) {
            throw SourceSyncContractException.unsupportedMinimumReaderVersion(envelope.minimumReaderVersion)
        }
        requireText(envelope.deviceId, "deviceId")
        requireText(envelope.snapshotId, "snapshotId")
        for (record in envelope.sources) {
            requireText(record.syncSourceId, "syncSourceId")
            requireText(record.sourceType.raw, "sourceType")
            for (endpoint in record.endpoints) requireText(endpoint.url, "url")
        }
        for (tombstone in envelope.deletions) requireText(tombstone.syncSourceId, "syncSourceId")
    }

    private fun requireText(value: String?, field: String) {
        if (value.isNullOrBlank()) throw SourceSyncContractException.missingField(field)
    }

    // ---------------------------------------------------------------- 读取

    fun decode(json: String): SourceSyncEnvelope {
        val root: JsonElement = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw SourceSyncContractException(SourceSyncContractException.MALFORMED_JSON, "The sync document is not valid JSON.", e)
        }
        if (root !is JsonObject) {
            throw SourceSyncContractException(SourceSyncContractException.MALFORMED_JSON, "The sync document must be a JSON object.")
        }

        val schemaVersion = requiredInt32(root, "schemaVersion")
        if (schemaVersion != SourceSyncEnvelope.CURRENT_SCHEMA_VERSION) {
            throw SourceSyncContractException.unsupportedSchemaVersion(schemaVersion)
        }
        val minimumReaderVersion = requiredInt32(root, "minimumReaderVersion")
        if (minimumReaderVersion < 1) {
            throw SourceSyncContractException.invalidField("minimumReaderVersion", "must be at least 1.")
        }
        if (minimumReaderVersion > SourceSyncEnvelope.CURRENT_SCHEMA_VERSION) {
            throw SourceSyncContractException.unsupportedMinimumReaderVersion(minimumReaderVersion)
        }

        val deviceId = requiredString(root, "deviceId")
        val snapshotId = requiredString(root, "snapshotId")
        val createdAtEpochMs = requiredInt64(root, "createdAtEpochMs")

        return SourceSyncEnvelope(
            schemaVersion = schemaVersion,
            minimumReaderVersion = minimumReaderVersion,
            deviceId = deviceId,
            snapshotId = snapshotId,
            createdAtEpochMs = createdAtEpochMs,
            capabilities = readCapabilities(root),
            sources = readArray(root, "sources")?.map { readRecord(it) } ?: emptyList(),
            deletions = readArray(root, "deletions")?.map { readTombstone(it) } ?: emptyList(),
        )
    }

    /** 与 decode 同义，但把失败变成返回值而不是异常。 */
    fun tryDecode(json: String): DecodeResult = try {
        DecodeResult.Ok(decode(json))
    } catch (e: SourceSyncContractException) {
        DecodeResult.Failed(e.reason, e.message)
    }

    private fun readCapabilities(root: JsonObject): SyncCapabilities {
        val value = root["capabilities"]
        if (value == null || value is JsonNull) return SyncCapabilities()
        if (value !is JsonObject) throw SourceSyncContractException.invalidField("capabilities", "must be an object.")
        return SyncCapabilities(
            canSendSecrets = optionalBoolean(value, "canSendSecrets") ?: false,
            // 名字与诊断字段分开传：键是 sourceTypes，报错时要报完整的 capabilities.sourceTypes。
            sourceTypes = stringArray(value, "sourceTypes", "capabilities.sourceTypes") ?: emptyList(),
        )
    }

    private fun readRecord(element: JsonElement): SourceRecord {
        if (element !is JsonObject) throw SourceSyncContractException.invalidField("sources", "must contain objects.")
        return SourceRecord(
            syncSourceId = requiredString(element, "syncSourceId"),
            sourceType = SyncSourceTypeValue.parse(requiredString(element, "sourceType")),
            displayName = optionalString(element, "displayName") ?: "",
            accountScope = optionalString(element, "accountScope"),
            endpoints = readArray(element, "endpoints")?.map { readEndpoint(it) } ?: emptyList(),
            recordRevision = requiredInt64(element, "recordRevision"),
            fieldVersion = optionalInt32(element, "fieldVersion") ?: 0,
            // secrets 只在本端不透明地搬运；即使内容非法也不解析、不校验。
            secrets = element["secrets"]?.takeIf { it !is JsonNull },
        )
    }

    private fun readEndpoint(element: JsonElement): SourceEndpoint {
        if (element !is JsonObject) throw SourceSyncContractException.invalidField("endpoints", "must be an object.")
        return SourceEndpoint(
            url = requiredString(element, "url"),
            isPrimary = optionalBoolean(element, "isPrimary") ?: false,
            enabled = optionalBoolean(element, "enabled") ?: true,
            sortOrder = optionalInt32(element, "sortOrder") ?: 0,
        )
    }

    private fun readTombstone(element: JsonElement): SourceTombstone {
        if (element !is JsonObject) throw SourceSyncContractException.invalidField("deletions", "must contain objects.")
        return SourceTombstone(
            syncSourceId = requiredString(element, "syncSourceId"),
            deletedAtEpochMs = optionalInt64(element, "deletedAtEpochMs") ?: 0L,
            recordRevision = optionalInt64(element, "recordRevision") ?: 0L,
        )
    }

    // ---------------------------------------------------------------- 读取助手

    private fun isPresent(value: JsonElement?): Boolean = value != null && value !is JsonNull

    private fun readArray(owner: JsonObject, name: String): List<JsonElement>? {
        val value = owner[name]
        if (!isPresent(value)) return null
        if (value !is JsonArray) throw SourceSyncContractException.invalidField(name, "must be an array.")
        return value
    }

    private fun stringArray(owner: JsonObject, name: String, field: String = name): List<String>? {
        val value = owner[name]
        if (!isPresent(value)) return null
        if (value !is JsonArray) throw SourceSyncContractException.invalidField(field, "must be an array.")
        return value.map {
            val primitive = it as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                throw SourceSyncContractException.invalidField(field, "must contain strings.")
            }
            primitive.content
        }
    }

    private fun requiredString(owner: JsonObject, name: String): String {
        val value = owner[name]
        if (!isPresent(value)) throw SourceSyncContractException.missingField(name)
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw SourceSyncContractException.invalidField(name, "must be a string.")
        }
        if (primitive.content.isBlank()) throw SourceSyncContractException.missingField(name)
        return primitive.content
    }

    private fun optionalString(owner: JsonObject, name: String): String? {
        val value = owner[name]
        if (!isPresent(value)) return null
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw SourceSyncContractException.invalidField(name, "must be a string.")
        }
        return primitive.content
    }

    private fun requiredInt32(owner: JsonObject, name: String): Int {
        val value = owner[name]
        if (!isPresent(value)) throw SourceSyncContractException.missingField(name)
        val primitive = value as? JsonPrimitive
        val number = if (primitive != null && !primitive.isString) primitive.intOrNull else null
        if (number == null) throw SourceSyncContractException.invalidField(name, "must be a 32-bit integer.")
        return number
    }

    private fun optionalInt32(owner: JsonObject, name: String): Int? {
        val value = owner[name]
        if (!isPresent(value)) return null
        val primitive = value as? JsonPrimitive
        val number = if (primitive != null && !primitive.isString) primitive.intOrNull else null
        if (number == null) throw SourceSyncContractException.invalidField(name, "must be a 32-bit integer.")
        return number
    }

    private fun requiredInt64(owner: JsonObject, name: String): Long {
        val value = owner[name]
        if (!isPresent(value)) throw SourceSyncContractException.missingField(name)
        val primitive = value as? JsonPrimitive
        val number = if (primitive != null && !primitive.isString) primitive.longOrNull else null
        if (number == null) throw SourceSyncContractException.invalidField(name, "must be a 64-bit integer.")
        return number
    }

    private fun optionalInt64(owner: JsonObject, name: String): Long? {
        val value = owner[name]
        if (!isPresent(value)) return null
        val primitive = value as? JsonPrimitive
        val number = if (primitive != null && !primitive.isString) primitive.longOrNull else null
        if (number == null) throw SourceSyncContractException.invalidField(name, "must be a 64-bit integer.")
        return number
    }

    private fun optionalBoolean(owner: JsonObject, name: String): Boolean? {
        val value = owner[name]
        if (!isPresent(value)) return null
        val primitive = value as? JsonPrimitive
        val flag = if (primitive != null && !primitive.isString) primitive.booleanOrNull else null
        if (flag == null) throw SourceSyncContractException.invalidField(name, "must be a boolean.")
        return flag
    }
}
