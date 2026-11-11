package com.mediahub.core.common.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceSyncCodecTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/sync/" + name)) { "缺少测试资源 " + name }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun sampleEnvelope(): SourceSyncEnvelope = SourceSyncEnvelope(
        deviceId = "dev-1",
        snapshotId = "snap-1",
        createdAtEpochMs = 1_700_000_000_000L,
        capabilities = SyncCapabilities(canSendSecrets = false, sourceTypes = listOf("emby")),
        sources = listOf(
            SourceRecord(
                syncSourceId = "local-1",
                sourceType = SyncSourceTypeValue.parse("emby"),
                displayName = "客厅 Emby",
                accountScope = "alice",
                endpoints = listOf(SourceEndpoint(url = "http://nas:8096/emby", isPrimary = true)),
                recordRevision = 3L,
                fieldVersion = 0,
            ),
        ),
        deletions = listOf(
            SourceTombstone(syncSourceId = "local-2", deletedAtEpochMs = 1_760_000_000_000L, recordRevision = 5L),
        ),
    )

    private fun reasonOf(json: String): String {
        try {
            SourceSyncCodec.decode(json)
        } catch (e: SourceSyncContractException) {
            return e.reason
        }
        fail("预期解码失败: " + json)
        throw IllegalStateException("unreachable")
    }

    @Test
    fun encoded_layout_is_exactly_the_contract_order() {
        val expected = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"dev-1\"," +
            "\"snapshotId\":\"snap-1\",\"createdAtEpochMs\":1700000000000," +
            "\"capabilities\":{\"canSendSecrets\":false,\"sourceTypes\":[\"emby\"]}," +
            "\"sources\":[{\"syncSourceId\":\"local-1\",\"sourceType\":\"emby\"," +
            "\"displayName\":\"客厅 Emby\",\"accountScope\":\"alice\"," +
            "\"endpoints\":[{\"url\":\"http://nas:8096/emby\",\"isPrimary\":true,\"enabled\":true,\"sortOrder\":0}]," +
            "\"recordRevision\":3,\"fieldVersion\":0}]," +
            "\"deletions\":[{\"syncSourceId\":\"local-2\",\"deletedAtEpochMs\":1760000000000,\"recordRevision\":5}]}"
        assertEquals(expected, SourceSyncCodec.encode(sampleEnvelope()))
    }

    @Test
    fun absent_optional_fields_are_omitted_not_written_as_null() {
        val envelope = sampleEnvelope().copy(
            sources = listOf(
                sampleEnvelope().sources[0].copy(accountScope = null, displayName = "", secrets = null),
            ),
            deletions = emptyList(),
        )
        val json = SourceSyncCodec.encode(envelope)
        assertFalse(json.contains("accountScope"))
        assertFalse(json.contains("secrets"))
        assertFalse(json.contains("null"))
    }

    @Test
    fun secrets_are_carried_verbatim_and_never_interpreted() {
        val blob: JsonElement = Json.parseToJsonElement("{\"k\":\"v\",\"n\":7}")
        val envelope = sampleEnvelope().copy(
            sources = listOf(sampleEnvelope().sources[0].copy(secrets = blob)),
        )
        val encoded = SourceSyncCodec.encode(envelope)
        assertTrue(encoded.contains("\"secrets\":{\"k\":\"v\",\"n\":7}"))

        val decoded = SourceSyncCodec.decode(encoded)
        assertEquals(blob, decoded.sources[0].secrets)
    }

    @Test
    fun roundtrip_preserves_every_field() {
        val decoded = SourceSyncCodec.decode(SourceSyncCodec.encode(sampleEnvelope()))
        assertEquals(sampleEnvelope(), decoded)
    }

    @Test
    fun golden_windows_envelope_decodes_without_losing_fields() {
        val envelope = SourceSyncCodec.decode(resource("windows-envelope-device-a.json"))
        assertEquals(1, envelope.schemaVersion)
        assertEquals(1, envelope.minimumReaderVersion)
        assertEquals("device-a", envelope.deviceId)
        assertEquals(1792784739843L, envelope.createdAtEpochMs)
        assertFalse(envelope.capabilities.canSendSecrets)
        assertEquals(listOf("emby", "jellyfin", "quantum-nas"), envelope.capabilities.sourceTypes)
        assertEquals(3, envelope.sources.size)
        assertEquals(1, envelope.deletions.size)

        val unknown = envelope.sources[2]
        assertEquals("quantum-nas", unknown.sourceType.raw)
        assertFalse(unknown.sourceType.isKnown)
        assertNull(unknown.accountScope)
        assertEquals("https://quantum.example:9443/v1", unknown.endpoints[0].url)
    }

    @Test
    fun decode_rejects_malformed_json_with_a_stable_reason() {
        assertEquals("malformed-json", reasonOf("{"))
        assertEquals("malformed-json", reasonOf("[]"))
    }

    @Test
    fun missing_schema_version_reports_missing_field() {
        assertEquals("missing-field:schemaVersion", reasonOf("{\"minimumReaderVersion\":1}"))
    }

    @Test
    fun future_schema_version_is_reported_with_its_number() {
        val json = "{\"schemaVersion\":2,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1}"
        assertEquals("unsupported-schema-version:2", reasonOf(json))
    }

    @Test
    fun minimum_reader_version_must_be_one_for_this_build() {
        val tooNew = "{\"schemaVersion\":1,\"minimumReaderVersion\":2,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1}"
        assertEquals("unsupported-minimum-reader-version:2", reasonOf(tooNew))

        val tooOld = "{\"schemaVersion\":1,\"minimumReaderVersion\":0,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1}"
        assertEquals("invalid-field:minimumReaderVersion", reasonOf(tooOld))
    }

    @Test
    fun required_envelope_strings_must_be_present_and_non_blank() {
        assertEquals(
            "missing-field:deviceId",
            reasonOf("{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"snapshotId\":\"s\",\"createdAtEpochMs\":1}"),
        )
        assertEquals(
            "missing-field:snapshotId",
            reasonOf("{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
                "\"snapshotId\":\"  \",\"createdAtEpochMs\":1}"),
        )
    }

    @Test
    fun numeric_fields_reject_strings() {
        val json = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":\"1\"}"
        assertEquals("invalid-field:createdAtEpochMs", reasonOf(json))
    }

    @Test
    fun sources_must_be_an_array_of_objects() {
        val notArray = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1,\"sources\":{}}"
        assertEquals("invalid-field:sources", reasonOf(notArray))

        val notObject = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1,\"sources\":[1]}"
        assertEquals("invalid-field:sources", reasonOf(notObject))
    }

    @Test
    fun record_requires_sync_source_id_and_revision() {
        val missingId = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1,\"sources\":[{\"sourceType\":\"emby\",\"recordRevision\":1}]}"
        assertEquals("missing-field:syncSourceId", reasonOf(missingId))

        val missingRevision = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1,\"sources\":[{\"syncSourceId\":\"x\",\"sourceType\":\"emby\"}]}"
        assertEquals("missing-field:recordRevision", reasonOf(missingRevision))
    }

    @Test
    fun endpoint_requires_a_url() {
        val json = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1,\"sources\":[{\"syncSourceId\":\"x\"," +
            "\"sourceType\":\"emby\",\"recordRevision\":1,\"endpoints\":[{\"isPrimary\":true}]}]}"
        assertEquals("missing-field:url", reasonOf(json))
    }

    @Test
    fun booleans_are_only_accepted_as_json_booleans() {
        val json = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1,\"sources\":[{\"syncSourceId\":\"x\"," +
            "\"sourceType\":\"emby\",\"recordRevision\":1,\"endpoints\":[{\"url\":\"http://h\"}," +
            "{\"url\":\"http://h\",\"isPrimary\":\"true\"}]}]}"
        assertEquals("invalid-field:isPrimary", reasonOf(json))
    }

    @Test
    fun optional_record_fields_default_like_the_windows_reader() {
        val json = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1,\"sources\":[{\"syncSourceId\":\"x\"," +
            "\"sourceType\":\"emby\",\"recordRevision\":1,\"endpoints\":[{\"url\":\"http://h\"}]}]," +
            "\"deletions\":[{\"syncSourceId\":\"y\"}]}"
        val envelope = SourceSyncCodec.decode(json)
        val record = envelope.sources[0]
        assertEquals("", record.displayName)
        assertNull(record.accountScope)
        assertEquals(0, record.fieldVersion)
        assertNull(record.secrets)
        assertFalse(record.endpoints[0].isPrimary)
        assertTrue(record.endpoints[0].enabled)
        assertEquals(0, record.endpoints[0].sortOrder)
        assertEquals(0L, envelope.deletions[0].deletedAtEpochMs)
        assertEquals(0L, envelope.deletions[0].recordRevision)
    }

    @Test
    fun unknown_properties_are_ignored_for_forward_compatibility() {
        val json = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1,\"futureField\":{\"a\":1}," +
            "\"capabilities\":{\"canSendSecrets\":true,\"sourceTypes\":[\"emby\"],\"extra\":2}}"
        val envelope = SourceSyncCodec.decode(json)
        assertTrue(envelope.capabilities.canSendSecrets)
        assertEquals(listOf("emby"), envelope.capabilities.sourceTypes)
    }

    @Test
    fun source_type_keeps_the_sender_spelling() {
        val json = "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"d\"," +
            "\"snapshotId\":\"s\",\"createdAtEpochMs\":1,\"sources\":[{\"syncSourceId\":\"x\"," +
            "\"sourceType\":\"Emby\",\"recordRevision\":0}]}"
        val record = SourceSyncCodec.decode(json).sources[0]
        assertEquals("Emby", record.sourceType.raw)
        assertEquals("emby", record.sourceType.wire)
    }

    @Test
    fun encode_refuses_to_write_a_document_this_build_cannot_read() {
        val blankDevice = sampleEnvelope().copy(deviceId = "  ")
        assertEquals("missing-field:deviceId", reasonOfEncode(blankDevice))

        val futureSchema = sampleEnvelope().copy(schemaVersion = 2)
        assertEquals("unsupported-schema-version:2", reasonOfEncode(futureSchema))

        val blankSourceId = sampleEnvelope().copy(
            sources = listOf(sampleEnvelope().sources[0].copy(syncSourceId = "")),
        )
        assertEquals("missing-field:syncSourceId", reasonOfEncode(blankSourceId))
    }

    private fun reasonOfEncode(envelope: SourceSyncEnvelope): String {
        try {
            SourceSyncCodec.encode(envelope)
        } catch (e: SourceSyncContractException) {
            return e.reason
        }
        fail("预期编码失败")
        throw IllegalStateException("unreachable")
    }

    @Test
    fun try_decode_reports_failures_as_values() {
        val ok = SourceSyncCodec.tryDecode(SourceSyncCodec.encode(sampleEnvelope()))
        assertTrue(ok is SourceSyncCodec.DecodeResult.Ok)

        val failed = SourceSyncCodec.tryDecode("{")
        assertTrue(failed is SourceSyncCodec.DecodeResult.Failed)
        assertEquals("malformed-json", (failed as SourceSyncCodec.DecodeResult.Failed).reason)
    }
}
