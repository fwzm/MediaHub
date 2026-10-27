package com.mediahub.core.common.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

/**
 * 同步基线（W6-72）的跨语言契约：内容哈希的规范 JSON、基线序列化字节、编解码判定序、
 * 合并语义、内存/文件存储、端点策略。
 *
 * 所有期望值都来自 Windows 权威实现跑出的黄金向量 baseline-vectors.json，不手写期望：
 * 同一份 JSON 也由 C# 的 BaselineContractTests 消费，任一端偏离都会有一套红。
 */
class SourceSyncBaselineContractTest {

    private val root: JsonObject = Json.parseToJsonElement(readResource()).jsonObject

    private fun readResource(): String {
        val stream = javaClass.getResourceAsStream(RESOURCE_PATH)
        requireNotNull(stream) { "找不到同步基线黄金向量资源 " + RESOURCE_PATH }
        return stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    }

    private fun entries(name: String): List<JsonObject> = root.getValue(name).jsonArray.map { it.jsonObject }

    private fun text(element: JsonElement): String = element.jsonPrimitive.content

    private fun nullableText(element: JsonElement?): String? {
        if (element == null) return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.content
    }

    private fun text(entry: JsonObject, name: String): String = text(entry.getValue(name))

    private fun nullableText(entry: JsonObject, name: String): String? = nullableText(entry[name])

    private fun boolean(entry: JsonObject, name: String): Boolean = text(entry, name) == "true"

    private fun int(entry: JsonObject, name: String): Int = text(entry, name).toInt()

    private fun long(entry: JsonObject, name: String): Long = text(entry, name).toLong()

    private fun endpointOf(element: JsonElement): SourceEndpoint {
        val entry = element.jsonObject
        return SourceEndpoint(
            url = text(entry, "url"),
            isPrimary = boolean(entry, "isPrimary"),
            enabled = boolean(entry, "enabled"),
            sortOrder = int(entry, "sortOrder"),
        )
    }

    private fun recordOf(element: JsonElement): SourceRecord {
        val entry = element.jsonObject
        return SourceRecord(
            syncSourceId = text(entry, "syncSourceId"),
            sourceType = SyncSourceTypeValue.parse(text(entry, "sourceTypeRaw")),
            displayName = text(entry, "displayName"),
            accountScope = nullableText(entry, "accountScope"),
            endpoints = entry.getValue("endpoints").jsonArray.map { endpointOf(it) },
            recordRevision = long(entry, "recordRevision"),
            fieldVersion = int(entry, "fieldVersion"),
            // 不透明片段：原样带过来。合并语义要按它判「这条带秘密，必须人工确认」，
            // 若在这里丢掉，secrets-require-approval 就会静默变成一次直接写入。
            secrets = entry["secrets"]?.takeIf { it !is JsonNull },
        )
    }

    private fun recordsOf(element: JsonElement?): List<SourceRecord> {
        if (element == null || element is JsonNull) return emptyList()
        return element.jsonArray.map { recordOf(it) }
    }

    private fun entryOf(element: JsonElement): SourceSyncBaselineEntry {
        val entry = element.jsonObject
        return SourceSyncBaselineEntry(
            syncSourceId = text(entry, "syncSourceId"),
            recordRevision = long(entry, "recordRevision"),
            fieldVersion = int(entry, "fieldVersion"),
            contentHash = text(entry, "contentHash"),
            fieldHashes = entry.getValue("fieldHashes").jsonObject.mapValues { text(it.value) },
        )
    }

    private fun baselineOf(element: JsonElement): SourceSyncBaseline {
        val entry = element.jsonObject
        return SourceSyncBaseline(
            version = int(entry, "version"),
            deviceId = nullableText(entry, "deviceId"),
            snapshotId = nullableText(entry, "snapshotId"),
            entries = entry.getValue("entries").jsonArray.map { entryOf(it) },
        )
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX_LOWER[value ushr 4]).append(HEX_LOWER[value and 0x0F])
        }
        return builder.toString()
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("mh-sync-baseline-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    // ------------------------------------------------------------ 常量与码表

    @Test
    fun constants_and_reason_codes_match_the_windows_contract() {
        val constants = root.getValue("constants").jsonObject
        assertEquals(
            "contentHashFields",
            constants.getValue("contentHashFields").jsonArray.map { text(it) },
            SourceSyncContentHash.FIELDS,
        )
        assertEquals("baselineCurrentVersion", int(constants, "baselineCurrentVersion"), SourceSyncBaseline.CURRENT_VERSION)
        assertEquals("codecSchemaVersion", int(constants, "codecSchemaVersion"), SourceSyncBaselineCodec.CURRENT_SCHEMA_VERSION)
        assertEquals("codecMaxEntries", int(constants, "codecMaxEntries"), SourceSyncBaselineCodec.MAX_ENTRIES)
        assertEquals("codecMaxFieldHashes", int(constants, "codecMaxFieldHashes"), SourceSyncBaselineCodec.MAX_FIELD_HASHES)
        assertEquals("codecMaxDepth", int(constants, "codecMaxDepth"), SourceSyncBaselineCodec.MAX_DEPTH)
        assertEquals("codecMaxDocumentChars", long(constants, "codecMaxDocumentChars"), SourceSyncBaselineCodec.MAX_DOCUMENT_CHARS)
        assertEquals("fileDocumentVersion", int(constants, "fileDocumentVersion"), FileSourceSyncBaselineStore.CURRENT_DOCUMENT_VERSION)
        assertEquals("fileMaxPeers", int(constants, "fileMaxPeers"), FileSourceSyncBaselineStore.MAX_PEERS)
        assertEquals("fileMaxDocumentChars", long(constants, "fileMaxDocumentChars"), FileSourceSyncBaselineStore.MAX_DOCUMENT_CHARS)
        assertEquals("fileName", text(constants, "fileName"), FileSourceSyncBaselineStore.FILE_NAME)
        assertEquals("corruptPrefix", text(constants, "corruptPrefix"), FileSourceSyncBaselineStore.CORRUPT_PREFIX)

        val reasons = root.getValue("reasons").jsonObject
        assertEquals(
            "codec reasons",
            reasons.getValue("codec").jsonArray.map { text(it) },
            listOf(
                SourceSyncBaselineCodecReasons.MALFORMED_JSON,
                SourceSyncBaselineCodecReasons.TOO_LARGE,
                SourceSyncBaselineCodecReasons.TOO_DEEP,
            ),
        )
        assertEquals(
            "store reasons",
            reasons.getValue("store").jsonArray.map { text(it) },
            listOf(
                SourceSyncBaselineStoreReasons.STALE_REVISION,
                SourceSyncBaselineStoreReasons.UNRESOLVED_CONFLICT,
                SourceSyncBaselineStoreReasons.STORE_UNREADABLE,
                SourceSyncBaselineStoreReasons.WRITE_FAILED,
            ),
        )
        assertEquals(
            "endpoint reasons",
            reasons.getValue("endpoint").jsonArray.map { text(it) },
            listOf(SourceSyncEndpointPolicyReasons.SECONDARY_NOT_IMPORTED),
        )
    }

    // ------------------------------------------------------------ 内容哈希

    @Test
    fun content_hash_cases_match_the_windows_contract() {
        val cases = entries("contentHashCases")
        assertEquals("contentHashCases 条数", 11, cases.size)
        for (entry in cases) {
            val name = text(entry, "name")
            val record = recordOf(entry.getValue("record"))
            assertEquals(name + " (canonical)", text(entry, "canonical"), SourceSyncContentHash.canonicalize(record))
            assertEquals(name + " (hash)", text(entry, "contentHash"), SourceSyncContentHash.compute(record))
            val expectedFields = entry.getValue("fieldHashes").jsonObject.mapValues { text(it.value) }
            assertEquals(name + " (field hashes)", expectedFields, SourceSyncContentHash.computeFields(record))
        }
    }

    // ------------------------------------------------------------ 基线构造

    @Test
    fun baseline_from_records_and_envelope_cases_match_the_windows_contract() {
        for (entry in entries("baselineCases")) {
            val name = text(entry, "name")
            val expectedJson = entry.getValue("baseline").jsonObject
            val expected = baselineOf(expectedJson)
            val expectedEmpty = boolean(expectedJson, "isEmpty")
            when (text(entry, "source")) {
                "records" -> {
                    val produced = SourceSyncBaseline.fromRecords(
                        recordsOf(entry["records"]),
                        nullableText(entry, "deviceId"),
                        nullableText(entry, "snapshotId"),
                    )
                    assertEquals(name, expected, produced)
                    assertEquals(name + " (isEmpty)", expectedEmpty, produced.isEmpty)
                }
                "envelope" -> {
                    val envelope = SourceSyncEnvelope(
                        deviceId = nullableText(entry, "deviceId") ?: "",
                        snapshotId = nullableText(entry, "snapshotId") ?: "",
                        sources = recordsOf(entry["records"]),
                    )
                    val produced = SourceSyncBaseline.fromEnvelope(envelope)
                    assertEquals(name, expected, produced)
                    assertEquals(name + " (isEmpty)", expectedEmpty, produced.isEmpty)
                }
                "empty" -> {
                    assertEquals(name, expected, SourceSyncBaseline.EMPTY)
                    assertEquals(name + " (isEmpty)", expectedEmpty, SourceSyncBaseline.EMPTY.isEmpty)
                }
                "advance" -> Unit // advance 依赖 SourceSyncDigest，Android 侧没有该类型，由 C# 孪生用例覆盖
                else -> error("未知来源 " + text(entry, "source"))
            }
        }
    }

    @Test
    fun find_cases_match_the_windows_contract() {
        val baseline = SourceSyncBaseline.fromRecords(
            listOf(
                record("find-a", "A", 1),
                record("find-b", "B", 2),
            ),
        )
        for (entry in entries("findCases")) {
            val key = nullableText(entry, "key")
            val found = baseline.find(key)
            assertEquals(key ?: "null", boolean(entry, "found"), found != null)
            if (boolean(entry, "found")) {
                assertEquals(key ?: "null", text(entry, "syncSourceId"), found?.syncSourceId)
                assertEquals(key ?: "null", long(entry, "recordRevision"), found?.recordRevision)
            } else {
                assertNull(key ?: "null", found)
            }
        }
    }

    private fun record(syncSourceId: String, displayName: String, recordRevision: Long): SourceRecord =
        SourceRecord(
            syncSourceId = syncSourceId,
            sourceType = SyncSourceTypeValue.parse("webdav"),
            displayName = displayName,
            recordRevision = recordRevision,
            fieldVersion = 1,
        )

    // ------------------------------------------------------------ 编解码：写

    @Test
    fun codec_write_cases_match_the_windows_contract_byte_for_byte() {
        for (entry in entries("codecWriteCases")) {
            val name = text(entry, "name")
            val baseline = baselineOf(entry.getValue("baseline"))
            val indented = SourceSyncBaselineCodec.serialize(baseline)
            val indentedBytes = SourceSyncBaselineCodec.serializeToBytes(baseline)
            val compact = SourceSyncBaselineCodec.serialize(baseline, indented = false)
            val compactBytes = SourceSyncBaselineCodec.serializeToBytes(baseline, indented = false)
            assertEquals(name + " (indented text)", text(entry, "indentedText"), indented)
            assertEquals(name + " (indented base64)", text(entry, "indentedBase64"), Base64.getEncoder().encodeToString(indentedBytes))
            assertEquals(name + " (indented bytes)", int(entry, "indentedByteLength"), indentedBytes.size)
            assertEquals(name + " (indented sha256)", text(entry, "indentedSha256"), sha256(indentedBytes))
            assertEquals(name + " (compact text)", text(entry, "compactText"), compact)
            assertEquals(name + " (compact base64)", text(entry, "compactBase64"), Base64.getEncoder().encodeToString(compactBytes))
            assertEquals(name + " (compact bytes)", int(entry, "compactByteLength"), compactBytes.size)
            assertEquals(name + " (compact sha256)", text(entry, "compactSha256"), sha256(compactBytes))
            assertEquals(name + " (has CR)", boolean(entry, "indentedHasCarriageReturn"), indented.contains(13.toChar()))
            assertEquals(name + " (ends with newline)", boolean(entry, "indentedEndsWithNewline"), indented.endsWith(10.toChar()))
        }
    }

    // ------------------------------------------------------------ 编解码：读

    @Test
    fun codec_load_cases_match_the_windows_contract() {
        for (entry in entries("codecLoadCases")) {
            val name = text(entry, "name")
            val json = nullableText(entry, "json")
            val expectedError = nullableText(entry, "error")
            val result = SourceSyncBaselineCodec.tryDeserialize(json)
            if (boolean(entry, "ok")) {
                assertTrue(name + " 应成功", result is SourceSyncBaselineCodec.DecodeResult.Ok)
                val ok = result as SourceSyncBaselineCodec.DecodeResult.Ok
                assertEquals(name + " (ignored)", int(entry, "ignoredFieldCount"), ok.ignoredFieldCount)
                assertEquals(name + " (baseline)", baselineOf(entry.getValue("baseline")), ok.baseline)
            } else {
                assertTrue(name + " 应失败", result is SourceSyncBaselineCodec.DecodeResult.Failed)
                val failed = result as SourceSyncBaselineCodec.DecodeResult.Failed
                assertEquals(name + " (reason)", expectedError, failed.reason)
            }
        }
    }

    // ------------------------------------------------------------ 合并

    @Test
    fun merge_cases_match_the_windows_contract() {
        for (entry in entries("mergeCases")) {
            val name = text(entry, "name")
            val existing = entry["existing"]?.takeIf { it !is JsonNull }?.let { baselineOf(it) }
            val incoming = entry["incoming"]?.takeIf { it !is JsonNull }?.let { baselineOf(it) }
            val result = SourceSyncBaselineMerge.merge(existing, incoming)
            assertEquals(name + " (changed)", boolean(entry, "changed"), result.changed)
            assertEquals(name + " (reason)", nullableText(entry, "reason"), result.reason)
            assertEquals(
                name + " (rejected)",
                entry.getValue("rejectedSyncSourceIds").jsonArray.map { text(it) },
                result.rejectedSyncSourceIds,
            )
            assertEquals(name + " (merged)", baselineOf(entry.getValue("merged")), result.merged)
        }
    }

    // ------------------------------------------------------------ 内存存储

    @Test
    fun in_memory_store_cases_match_the_windows_contract() {
        val store = InMemorySourceSyncBaselineStore()
        for (entry in entries("inMemoryCases")) {
            val name = text(entry, "name")
            if (name == "final-state") {
                assertEquals("final-state (writeCount)", int(entry, "writeCount"), store.writeCount)
                assertEquals("final-state (knownPeers)", entry.getValue("knownPeers").jsonArray.map { text(it) }, store.knownPeerDeviceIds)
                assertEquals("final-state (peerA)", baselineOf(entry.getValue("peerA")), store.load("peer-a"))
                assertEquals("final-state (peerB)", baselineOf(entry.getValue("peerB")), store.load("peer-b"))
                continue
            }
            if (name == "load-unknown") {
                assertEquals("load-unknown (isEmpty)", boolean(entry, "isEmpty"), store.load(text(entry, "peerDeviceId")).isEmpty)
                assertEquals("load-unknown (writeCount)", int(entry, "writeCount"), store.writeCount)
                assertEquals("load-unknown (knownPeers)", entry.getValue("knownPeers").jsonArray.map { text(it) }, store.knownPeerDeviceIds)
                continue
            }
            val result = store.save(text(entry, "peerDeviceId"), baselineOf(entry.getValue("baseline")))
            assertEquals(name + " (saved)", boolean(entry, "saved"), result.saved)
            assertEquals(name + " (unchanged)", boolean(entry, "unchanged"), result.unchanged)
            assertEquals(
                name + " (rejected)",
                entry.getValue("rejectedSyncSourceIds").jsonArray.map { text(it) },
                result.rejectedSyncSourceIds,
            )
            assertEquals(name + " (reason)", nullableText(entry, "reason"), result.reason)
            assertEquals(name + " (writeCount)", int(entry, "writeCount"), store.writeCount)
        }
    }

    // ------------------------------------------------------------ 文件存储：文档字节

    @Test
    fun file_store_document_cases_match_the_windows_contract_byte_for_byte() {
        for (entry in entries("fileDocumentCases")) {
            val name = text(entry, "name")
            withTempDirectory { directory ->
                val store = FileSourceSyncBaselineStore(directory)
                for (write in entry.getValue("writes").jsonArray.map { it.jsonObject }) {
                    store.save(text(write, "peerDeviceId"), baselineOf(write.getValue("baseline")))
                }
                val bytes = File(store.filePath).readBytes()
                assertEquals(name + " (fileName)", text(entry, "fileName"), File(store.filePath).name)
                assertEquals(name + " (base64)", text(entry, "base64"), Base64.getEncoder().encodeToString(bytes))
                assertEquals(name + " (text)", text(entry, "text"), String(bytes, StandardCharsets.UTF_8))
                assertEquals(name + " (bytes)", int(entry, "byteLength"), bytes.size)
                assertEquals(name + " (sha256)", text(entry, "sha256"), sha256(bytes))
                assertEquals(name + " (has CR)", boolean(entry, "hasCarriageReturn"), String(bytes, StandardCharsets.UTF_8).contains(13.toChar()))
                assertEquals(name + " (writeCount)", int(entry, "writeCount"), store.writeCount)
                assertEquals(name + " (peers)", entry.getValue("peerDeviceIds").jsonArray.map { text(it) }, store.peerDeviceIds)
                assertEquals(name + " (backup)", boolean(entry, "backupExists"), File(store.backupPath).exists())
            }
        }
    }

    // ------------------------------------------------------------ 文件存储：加载与隔离

    @Test
    fun file_store_load_cases_match_the_windows_contract() {
        for (entry in entries("fileLoadCases")) {
            val name = text(entry, "name")
            val content = nullableText(entry, "content")
            withTempDirectory { directory ->
                if (content != null) {
                    File(directory, FileSourceSyncBaselineStore.FILE_NAME).writeText(content, StandardCharsets.UTF_8)
                }
                val store = FileSourceSyncBaselineStore(directory)
                val loaded = store.load("p-b")
                val corrupt = directory.listFiles()?.filter {
                    it.name.startsWith(FileSourceSyncBaselineStore.CORRUPT_PREFIX)
                } ?: emptyList()
                assertEquals(name + " (loadError)", nullableText(entry, "loadError"), store.loadError)
                assertEquals(name + " (peers)", entry.getValue("peerDeviceIds").jsonArray.map { text(it) }, store.peerDeviceIds)
                assertEquals(name + " (peerB)", baselineOf(entry.getValue("peerB")), loaded)
                assertEquals(name + " (peerA)", baselineOf(entry.getValue("peerA")), store.load("p-a"))
                assertEquals(name + " (corruptCount)", int(entry, "corruptFileCount"), corrupt.size)
                assertEquals(name + " (quarantined)", boolean(entry, "quarantined"), store.lastQuarantinedPath != null)
                assertEquals(name + " (fileExists)", boolean(entry, "fileStillExists"), File(store.filePath).exists())
            }
        }
    }

    // ------------------------------------------------------------ 端点策略

    @Test
    fun endpoint_policy_cases_match_the_windows_contract() {
        for (entry in entries("endpointPolicyCases")) {
            val name = text(entry, "name")
            val endpoints = entry.getValue("endpoints").jsonArray.map { endpointOf(it) }
            val record = SourceRecord(
                syncSourceId = "ep-" + name,
                sourceType = SyncSourceTypeValue.parse("webdav"),
                displayName = "Endpoints",
                endpoints = endpoints,
                recordRevision = 1,
                fieldVersion = 1,
            )
            val primary = SourceSyncEndpointPolicy.selectPrimary(record)
            assertEquals(name + " (primary url)", nullableText(entry, "primaryUrl"), primary?.url)
            assertEquals(name + " (primary sortOrder)", nullableText(entry, "primarySortOrder")?.toInt(), primary?.sortOrder)
            val notImported = SourceSyncEndpointPolicy.notImported(record)
            val expectedCount = int(entry, "notImportedCount")
            assertEquals(name + " (count)", expectedCount, notImported.size)
            val expected = entry.getValue("notImported").jsonArray.map { it.jsonObject }
            for (index in expected.indices) {
                val want = expected[index]
                val actual = notImported[index]
                val label = name + "[" + index + "]"
                assertEquals(label + " (id)", text(want, "syncSourceId"), actual.syncSourceId)
                assertEquals(label + " (name)", text(want, "displayName"), actual.displayName)
                assertEquals(label + " (url)", text(want, "url"), actual.url)
                assertEquals(label + " (normalized)", nullableText(want, "normalizedUrl"), actual.normalizedUrl)
                assertEquals(label + " (sortOrder)", int(want, "sortOrder"), actual.sortOrder)
                assertEquals(label + " (isPrimary)", boolean(want, "isPrimary"), actual.isPrimary)
                assertEquals(label + " (enabled)", boolean(want, "enabled"), actual.enabled)
                assertEquals(label + " (reason)", text(want, "reason"), actual.reason)
            }
        }
    }

    @Test
    fun endpoint_summary_cases_match_the_windows_contract() {
        for (entry in entries("endpointSummaryCases")) {
            val name = text(entry, "name")
            val sources = recordsOf(entry["sources"])
            val envelope = SourceSyncEnvelope(deviceId = "dev", snapshotId = "snap", sources = sources)
            val summary = SourceSyncEndpointPolicy.summarize(envelope)
            assertEquals(name + " (count)", int(entry, "notImportedCount"), summary.notImportedCount)
            assertEquals(name + " (alternatives)", int(entry, "recordsWithAlternatives"), summary.recordsWithAlternatives)
            assertEquals(name + " (without)", int(entry, "recordsWithoutEndpoint"), summary.recordsWithoutEndpoint)
            assertEquals(name + " (isEmpty)", boolean(entry, "isEmpty"), summary.isEmpty)
            val expected = entry.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(name + " (items 条数)", expected.size, summary.items.size)
            for (index in expected.indices) {
                val want = expected[index]
                val actual = summary.items[index]
                val label = name + "[" + index + "]"
                assertEquals(label + " (id)", text(want, "syncSourceId"), actual.syncSourceId)
                assertEquals(label + " (url)", text(want, "url"), actual.url)
                if (want.containsKey("normalizedUrl")) {
                    assertEquals(label + " (normalized)", nullableText(want, "normalizedUrl"), actual.normalizedUrl)
                }
                assertEquals(label + " (isPrimary)", boolean(want, "isPrimary"), actual.isPrimary)
            }
        }
    }

    // ------------------------------------------------------------ 摘要（W6-73）

    private fun kindOf(name: String): SourceSyncChangeKind = when (name) {
        "Added" -> SourceSyncChangeKind.ADDED
        "Updated" -> SourceSyncChangeKind.UPDATED
        "Removed" -> SourceSyncChangeKind.REMOVED
        else -> throw IllegalArgumentException("未知的变化名 " + name)
    }

    private fun directionOf(name: String): SyncMergeDirection = when (name) {
        "Push" -> SyncMergeDirection.PUSH
        "Pull" -> SyncMergeDirection.PULL
        "Both" -> SyncMergeDirection.BOTH
        else -> throw IllegalArgumentException("未知的方向名 " + name)
    }

    private fun tombstoneOf(element: JsonElement): SourceTombstone {
        val entry = element.jsonObject
        return SourceTombstone(
            syncSourceId = text(entry, "syncSourceId"),
            deletedAtEpochMs = long(entry, "deletedAtEpochMs"),
            recordRevision = long(entry, "recordRevision"),
        )
    }

    private fun tombstonesOf(element: JsonElement?): List<SourceTombstone> {
        if (element == null || element is JsonNull) return emptyList()
        return element.jsonArray.map { tombstoneOf(it) }
    }

    private fun issueOf(element: JsonElement): SourceSyncDigestIssue {
        val entry = element.jsonObject
        return SourceSyncDigestIssue(
            syncSourceId = nullableText(entry, "syncSourceId") ?: "",
            reason = text(entry, "reason"),
        )
    }

    private fun digestItemOf(element: JsonElement): SourceSyncDigestItem {
        val entry = element.jsonObject
        return SourceSyncDigestItem(
            syncSourceId = text(entry, "syncSourceId"),
            kind = kindOf(text(entry, "kind")),
            record = entry["record"]?.takeIf { it !is JsonNull }?.let { recordOf(it) },
            tombstone = entry["tombstone"]?.takeIf { it !is JsonNull }?.let { tombstoneOf(it) },
            changedFields = entry.getValue("changedFields").jsonArray.map { text(it) },
            contentChanged = boolean(entry, "contentChanged"),
            recordRevision = long(entry, "recordRevision"),
            fieldVersion = int(entry, "fieldVersion"),
        )
    }

    private fun digestOf(element: JsonElement): SourceSyncDigest {
        val entry = element.jsonObject
        return SourceSyncDigest(
            items = entry.getValue("items").jsonArray.map { digestItemOf(it) },
            issues = entry.getValue("issues").jsonArray.map { issueOf(it) },
            isFullSnapshot = boolean(entry, "isFullSnapshot"),
        )
    }

    private fun roundEntryOf(element: JsonElement): SyncRoundEntry {
        val entry = element.jsonObject
        return SyncRoundEntry(
            syncSourceId = text(entry, "syncSourceId"),
            direction = directionOf(text(entry, "direction")),
            kind = kindOf(text(entry, "kind")),
            reason = text(entry, "reason"),
            record = entry["record"]?.takeIf { it !is JsonNull }?.let { recordOf(it) },
            tombstone = entry["tombstone"]?.takeIf { it !is JsonNull }?.let { tombstoneOf(it) },
            conflictingFields = entry.getValue("conflictingFields").jsonArray.map { text(it) },
            localRevision = long(entry, "localRevision"),
            remoteRevision = long(entry, "remoteRevision"),
            requiresApproval = boolean(entry, "requiresApproval"),
        )
    }

    private fun roundResultOf(element: JsonElement): SyncRoundResult {
        val entry = element.jsonObject
        return SyncRoundResult.create(
            apply = entry.getValue("apply").jsonArray.map { roundEntryOf(it) },
            requiresApproval = entry.getValue("requiresApproval").jsonArray.map { roundEntryOf(it) },
            ignored = entry.getValue("ignored").jsonArray.map { roundEntryOf(it) },
        )
    }

    @Test
    fun digest_cases_match_the_windows_contract() {
        for (entry in entries("digestCases")) {
            val name = text(entry, "name")
            val baseline = entry["baseline"]?.takeIf { it !is JsonNull }?.let { baselineOf(it) }
            val digest = SourceSyncDigest.build(
                records = recordsOf(entry["records"]),
                baseline = baseline,
                deletions = tombstonesOf(entry["deletions"]),
            )
            val want = entry.getValue("digest").jsonObject
            assertEquals(name + " (items)", digestOf(want).items, digest.items)
            assertEquals(name + " (issues)", digestOf(want).issues, digest.issues)
            assertEquals(name + " (isFullSnapshot)", boolean(want, "isFullSnapshot"), digest.isFullSnapshot)
            assertEquals(name + " (isEmpty)", boolean(want, "isEmpty"), digest.isEmpty)
            assertEquals(name + " (addedCount)", int(want, "addedCount"), digest.added.size)
            assertEquals(name + " (updatedCount)", int(want, "updatedCount"), digest.updated.size)
            assertEquals(name + " (removedCount)", int(want, "removedCount"), digest.removed.size)
        }
    }

    @Test
    fun merger_cases_match_the_windows_contract() {
        var compared = 0
        val skipped = ArrayList<String>()
        for (entry in entries("mergerCases")) {
            val name = text(entry, "name")
            val local = digestOf(entry.getValue("local"))
            val remote = digestOf(entry.getValue("remote"))
            val baseline = entry["baseline"]?.takeIf { it !is JsonNull }?.let { baselineOf(it) }
            if (boolean(entry, "windowsOnly")) {
                // 这一条的期望值是「Windows 没有对应类型」。Android 的 ServerType 覆盖全部 12 个 wire 值，
                // 所以必须能导入；跳过断言，但要证明跳过理由成立：类型在 Android 侧确实可解析。
                skipped.add(name)
                for (item in remote.items) {
                    val record = item.record ?: continue
                    val resolution = AndroidSourceTypeMap.resolve(record.sourceType)
                    assertTrue(
                        name + " (" + record.sourceType.wire + " 在 Android 侧应可解析)",
                        resolution.isKnown && resolution.isSupported,
                    )
                }
                continue
            }
            val want = entry.getValue("result").jsonObject
            val actual = SyncRoundMerger.merge(local, remote, baseline)
            assertEquals(name + " (apply)", roundResultOf(want).apply, actual.apply)
            assertEquals(name + " (requiresApproval)", roundResultOf(want).requiresApproval, actual.requiresApproval)
            assertEquals(name + " (ignored)", roundResultOf(want).ignored, actual.ignored)
            assertEquals(name + " (hasWork)", boolean(want, "hasWork"), actual.hasWork)
            compared++
        }
        assertEquals("只有 windowsOnly 的那一条跳过", entries("mergerCases").size - 1, compared)
        assertEquals(listOf("windows-does-not-support-cloud-types"), skipped)
    }

    // ------------------------------------------------------------ 规划器（W6-75）

    /**
     * 规划器决定每次导入能不能落库，W6-75 之前它只有两端各自手写的用例，没有跨语言黄金向量。
     * 每个用例同时钉两件事：`expect` 是这条用例要证明的规则（生成向量时由 Windows 权威自证），
     * `plan.items` 是权威算出的整条建议（含 serverId、修订号、是否需要人工确认）。
     * windowsOnly 的用例不能整段跳过：Windows 产不出的动作在本端必须可用，跳过就等于放弃对照。
     */
    @Test
    fun planner_cases_match_the_windows_contract() {
        var compared = 0
        val skipped = ArrayList<String>()
        for (entry in entries("plannerCases")) {
            val name = text(entry, "name")
            val envelopeElement = entry.getValue("envelope").jsonObject
            if (boolean(entry, "windowsOnly")) {
                val supports = boolean(entry, "androidSupports")
                for (record in recordsOf(envelopeElement["sources"])) {
                    val resolution = AndroidSourceTypeMap.resolve(record.sourceType)
                    assertEquals(
                        "$name|android|${record.sourceType.wire}",
                        supports,
                        resolution.isKnown && resolution.isSupported,
                    )
                }
                skipped.add(name)
                continue
            }

            val locals = entry.getValue("locals").jsonArray.map { localRecordOf(it) }
            val envelope = SourceSyncEnvelope(
                sources = recordsOf(envelopeElement["sources"]),
                deletions = tombstonesOf(envelopeElement["deletions"]),
            )
            val plan = SourceSyncPlanner.plan(envelope, locals)

            val expect = entry.getValue("expect").jsonArray.map { text(it) }
            assertEquals(
                "$name|intent",
                expect.mapIndexed { index, value -> "intent $index|$value" },
                plan.items.mapIndexed { index, item -> "intent $index|${vectorNameOf(item.action)}/${item.reason}" },
            )
            assertEquals(
                "$name|items",
                entry.getValue("plan").jsonObject.getValue("items").jsonArray
                    .mapIndexed { index, value -> "item $index|${describeCandidate(value.jsonObject)}" },
                plan.items.mapIndexed { index, item -> "item $index|${describe(item)}" },
            )
            compared++
        }

        assertEquals("windowsOnly cases", listOf("windows-does-not-support-cloud-types", "windows-does-not-support-fn-nas"), skipped)
        assertEquals("compared cases", entries("plannerCases").size - skipped.size, compared)
    }

    private val actionNames: List<Pair<String, SourceSyncAction>> = listOf(
        "Create" to SourceSyncAction.CREATE,
        "Update" to SourceSyncAction.UPDATE,
        "NoChange" to SourceSyncAction.NO_CHANGE,
        "Conflict" to SourceSyncAction.CONFLICT,
        "IgnoreStale" to SourceSyncAction.IGNORE_STALE,
        "Delete" to SourceSyncAction.DELETE,
        "Adopt" to SourceSyncAction.ADOPT,
        "Unsupported" to SourceSyncAction.UNSUPPORTED,
    )

    private fun actionOf(name: String): SourceSyncAction = actionNames.firstOrNull { it.first == name }?.second
        ?: throw AssertionError("向量里出现未知动作 $name")

    private fun vectorNameOf(action: SourceSyncAction): String = actionNames.first { it.second == action }.first

    private fun localRecordOf(element: JsonElement): LocalSourceRecord {
        val entry = element.jsonObject
        val wire = nullableText(entry, "typeWire")
        return LocalSourceRecord(
            serverId = text(entry, "serverId"),
            syncSourceId = nullableText(entry, "syncSourceId"),
            type = AndroidSourceTypeMap.toServerType(wire),
            url = nullableText(entry, "url"),
            accountScope = nullableText(entry, "accountScope"),
            displayName = nullableText(entry, "displayName"),
            recordRevision = long(entry, "recordRevision"),
            sourceTypeRaw = wire,
        )
    }

    private fun describe(item: SourceSyncCandidate): String = listOf(
        item.syncSourceId ?: "",
        item.action.name,
        item.reason,
        item.serverId ?: "-",
        item.sourceType ?: "-",
        item.isKnownSourceType.toString(),
        item.normalizedUrl ?: "-",
        item.localRevision?.toString() ?: "-",
        item.remoteRevision?.toString() ?: "-",
        item.requiresUserApproval.toString(),
        item.candidateServerIds.joinToString(","),
    ).joinToString("|")

    private fun describeCandidate(entry: JsonObject): String = listOf(
        nullableText(entry, "syncSourceId") ?: "",
        actionOf(text(entry, "action")).name,
        text(entry, "reason"),
        candidateField(entry, "serverId"),
        candidateField(entry, "sourceType"),
        boolean(entry, "isKnownSourceType").toString(),
        candidateField(entry, "normalizedUrl"),
        candidateField(entry, "localRevision"),
        candidateField(entry, "remoteRevision"),
        boolean(entry, "requiresUserApproval").toString(),
        entry.getValue("candidateServerIds").jsonArray.map { text(it) }.joinToString(","),
    ).joinToString("|")

    private fun candidateField(entry: JsonObject, name: String): String = nullableText(entry, name) ?: "-"

    // ------------------------------------------------------------ 批准闸门（W6-77）

    @Test
    fun approval_cases_match_the_windows_contract() {
        val codes = root.getValue("approvalCodes").jsonObject
        assertEquals(
            "永不放行的原因码",
            listOf("secrets-require-approval", "unknown-source-type", "not-supported-on-windows"),
            codes.getValue("neverAllowable").jsonArray.map { text(it) },
        )
        assertEquals(
            "放行即覆盖的风险原因码",
            listOf("delete-vs-update", "update-vs-delete", "field-conflict", "same-revision-different-content"),
            codes.getValue("risky").jsonArray.map { text(it) },
        )
        assertEquals("模糊前缀", "ambiguous-", text(codes.getValue("ambiguousPrefix")))

        var compared = 0
        for (case in entries("approvalCases")) {
            val name = text(case, "name")
            val items = case.getValue("items").jsonArray.map { approvalItemOf(it) }
            val decisions = case.getValue("decisions").jsonArray.map { decisionOf(it) }
            assertEquals(name + ".rows", case.getValue("items").jsonArray.size, items.size)

            val error = nullableText(case, "error")
            if (error != null) {
                val thrown = runCatching { SourceSyncApprovalGate.decide(items, decisions) }.exceptionOrNull()
                assertTrue(name + " 应当抛 IllegalArgumentException，实际是 " + thrown, thrown is IllegalArgumentException)
                val message = (thrown as IllegalArgumentException).message ?: ""
                val expectedFragment = when (error) {
                    "blank-decision-identity" -> "decision needs a syncSourceId"
                    "blank-item-identity" -> "item needs a syncSourceId"
                    else -> throw IllegalArgumentException("未知的错误标签 " + error)
                }
                assertTrue(name + ".error 应提到 " + expectedFragment + "，实际是 " + message, message.contains(expectedFragment))
                compared++
                continue
            }

            val result = SourceSyncApprovalGate.decide(items, decisions)
            assertApproval(name, case.getValue("expect"), result)
            assertEquals(name + ".输入条数不变", items.size, result.appliedCount + result.skippedCount + result.withheldCount)
            compared++
        }
        assertEquals("比过的用例数", entries("approvalCases").size, compared)

        var comparedRounds = 0
        for (case in entries("approvalRoundCases")) {
            val name = text(case, "name")
            val round = roundResultOf(case.getValue("round"))
            val decisions = case.getValue("decisions").jsonArray.map { decisionOf(it) }

            // 适配器是这一段的主角：一轮条目没有 action，只有 kind + reason；行里显示的主地址也不做归一化。
            val rows = (round.apply + round.requiresApproval).map { SourceSyncApprovalItem.fromRoundEntry(it) }
            assertEquals(
                name + ".行投影",
                case.getValue("items").jsonArray.map { describeApprovalItem(it.jsonObject) },
                rows.map { describeApprovalItemRow(it) },
            )

            val result = SourceSyncApprovalGate.decide(round, decisions)
            assertApproval(name, case.getValue("expect"), result)
            assertEquals(
                name + ".ignored 不进闸门",
                round.ignored.size,
                case.getValue("round").jsonObject.getValue("ignored").jsonArray.size,
            )
            comparedRounds++
        }
        assertEquals("比过的轮次用例数", entries("approvalRoundCases").size, comparedRounds)
    }

    private fun assertApproval(name: String, expect: JsonElement, result: SourceSyncApprovalResult) {
        val entry = expect.jsonObject
        assertEquals(name + ".applied", entry.getValue("applied").jsonArray.map { text(it) }, result.applied.map { it.syncSourceId })
        assertEquals(name + ".skipped", entry.getValue("skipped").jsonArray.map { text(it) }, result.skipped.map { it.syncSourceId })
        assertEquals(name + ".withheld", entry.getValue("withheld").jsonArray.map { text(it) }, result.withheld.map { it.syncSourceId })
        assertEquals(
            name + ".blockingReasons",
            entry.getValue("blockingReasons").jsonArray.map { text(it) },
            result.blockingReasons,
        )
        assertEquals(name + ".acknowledgedRisk", boolean(entry, "acknowledgedRisk"), result.acknowledgedRisk)
        assertEquals(name + ".appliedWithRisk", int(entry, "appliedWithRisk"), result.appliedWithRisk)
        assertEquals(name + ".canApply", boolean(entry, "canApply"), result.canApply)
        assertEquals(name + ".isBlocked", boolean(entry, "isBlocked"), result.isBlocked)
        assertEquals(name + ".isEmpty", result.appliedCount + result.skippedCount + result.withheldCount == 0, result.isEmpty)
        val firstApplied = result.applied.firstOrNull()
        if (firstApplied != null) {
            assertTrue(name + ".isApplied(" + firstApplied.syncSourceId + ")", result.isApplied(firstApplied.syncSourceId))
            assertTrue(name + ".find(" + firstApplied.syncSourceId + ")", result.find(firstApplied.syncSourceId) === firstApplied)
        }
        val firstWithheld = result.withheld.firstOrNull()
        if (firstWithheld != null) {
            assertTrue(name + ".isWithheld(" + firstWithheld.syncSourceId + ")", result.isWithheld(firstWithheld.syncSourceId))
        }
    }

    private fun approvalItemOf(element: JsonElement): SourceSyncApprovalItem {
        val entry = element.jsonObject
        return SourceSyncApprovalItem(
            syncSourceId = text(entry, "syncSourceId"),
            reason = text(entry, "reason"),
            action = actionOf(text(entry, "action")),
            requiresApproval = boolean(entry, "requiresApproval"),
            displayName = nullableText(entry, "displayName"),
            serverId = nullableText(entry, "serverId"),
            sourceType = nullableText(entry, "sourceType"),
            normalizedUrl = nullableText(entry, "normalizedUrl"),
            localRevision = nullableLong(entry, "localRevision"),
            remoteRevision = nullableLong(entry, "remoteRevision"),
        )
    }

    private fun decisionOf(element: JsonElement): SourceSyncApprovalDecision {
        val entry = element.jsonObject
        val choice = when (text(entry, "choice")) {
            "Allow" -> SourceSyncApprovalChoice.ALLOW
            "Skip" -> SourceSyncApprovalChoice.SKIP
            else -> throw IllegalArgumentException("未知的选择 " + text(entry, "choice"))
        }
        return SourceSyncApprovalDecision(text(entry, "syncSourceId"), choice)
    }

    private fun nullableLong(entry: JsonObject, name: String): Long? = nullableText(entry, name)?.toLong()

    private fun describeApprovalItem(entry: JsonObject): String = listOf(
        text(entry, "syncSourceId"),
        actionOf(text(entry, "action")).name,
        text(entry, "reason"),
        boolean(entry, "requiresApproval").toString(),
        nullableText(entry, "displayName") ?: "-",
        nullableText(entry, "serverId") ?: "-",
        nullableText(entry, "sourceType") ?: "-",
        nullableText(entry, "normalizedUrl") ?: "-",
        nullableLong(entry, "localRevision")?.toString() ?: "-",
        nullableLong(entry, "remoteRevision")?.toString() ?: "-",
    ).joinToString("|")

    private fun describeApprovalItemRow(item: SourceSyncApprovalItem): String = listOf(
        item.syncSourceId,
        item.action.name,
        item.reason,
        item.requiresApproval.toString(),
        item.displayName ?: "-",
        item.serverId ?: "-",
        item.sourceType ?: "-",
        item.normalizedUrl ?: "-",
        item.localRevision?.toString() ?: "-",
        item.remoteRevision?.toString() ?: "-",
    ).joinToString("|")

    private companion object {
        private const val RESOURCE_PATH = "/sync/baseline-vectors.json"
        private const val HEX_LOWER = "0123456789abcdef"
    }
}
