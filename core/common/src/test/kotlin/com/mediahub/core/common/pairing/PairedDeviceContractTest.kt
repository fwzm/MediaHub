package com.mediahub.core.common.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64

/**
 * 配对设备记录的跨语言契约（W6-70）：12 条原因码、5 条存储码、容量语义、忘记语义、
 * 清单文案与 paired-devices.json 的磁盘字节。
 *
 * 所有期望值都来自 Windows 权威实现跑出的黄金向量 paired-device-vectors.json，不手写期望：
 * 同一份 JSON 也由 C# 的 PairedDeviceContractTests 消费，任一端偏离都会有一套红。
 */
class PairedDeviceContractTest {

    private val root: JsonObject = Json.parseToJsonElement(readResource()).jsonObject

    private val frozenNow: Long = root.getValue("frozenNowEpochMs").jsonPrimitive.long

    private val fingerprintA: String = "a".repeat(64)

    private val fingerprintB: String = "b".repeat(64)

    private val fingerprintC: String = "c".repeat(64)

    private fun readResource(): String {
        val stream = javaClass.getResourceAsStream(RESOURCE_PATH)
        requireNotNull(stream) { "找不到配对设备黄金向量资源 " + RESOURCE_PATH }
        return stream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
    }

    private fun entries(name: String): JsonArray = root.getValue(name).jsonArray

    private fun textOf(element: JsonElement): String = element.jsonPrimitive.content

    private fun textOf(entry: JsonObject, name: String): String = textOf(entry.getValue(name))

    private fun nullableTextOf(entry: JsonObject, name: String): String? {
        val element = entry[name] ?: return null
        if (element is JsonNull) {
            return null
        }
        return element.jsonPrimitive.content
    }

    private fun boolOf(entry: JsonObject, name: String): Boolean = textOf(entry, name) == "true"

    private fun deviceOf(element: JsonElement): PairedDevice {
        val entry = element.jsonObject
        return PairedDevice(
            deviceId = textOf(entry, "deviceId"),
            displayName = textOf(entry, "displayName"),
            fingerprint = textOf(entry, "fingerprint"),
            pairedAtEpochMs = entry.getValue("pairedAtEpochMs").jsonPrimitive.long,
            lastSeenEpochMs = entry.getValue("lastSeenEpochMs").jsonPrimitive.long,
            sourceCount = textOf(entry, "sourceCount").toInt(),
        )
    }

    private fun stateOf(name: String): PairedFingerprintState = when (name) {
        "new" -> PairedFingerprintState.NEW
        "match" -> PairedFingerprintState.MATCH
        "mismatch" -> PairedFingerprintState.MISMATCH
        else -> error("未知状态 " + name)
    }

    private fun stateName(state: PairedFingerprintState): String = when (state) {
        PairedFingerprintState.NEW -> "new"
        PairedFingerprintState.MATCH -> "match"
        PairedFingerprintState.MISMATCH -> "mismatch"
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("mh-paired-device-").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
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

    private fun seededRegistry(outStore: (InMemoryPairedDeviceStore) -> Unit): PairedDeviceRegistry {
        val store = InMemoryPairedDeviceStore()
        store.save(PairedDevice.create("device-a", fingerprintA, "家庭 NAS（客厅）", frozenNow, 3))
        store.save(PairedDevice.create("device-b", fingerprintB, "", frozenNow, 0))
        outStore(store)
        return PairedDeviceRegistry(store)
    }

    // ------------------------------------------------------------ 码表

    @Test
    fun reason_codes_and_their_descriptions_match_the_windows_contract() {
        val cases = entries("reasonCodes").map { it.jsonObject }
        assertEquals("reasonCodes 条数", 12, cases.size)
        assertEquals("reasonCodes 顺序", cases.map { textOf(it, "code") }, PairedDeviceReasons.ALL)
        for (entry in cases) {
            val code = textOf(entry, "code")
            assertEquals(code, textOf(entry, "description"), PairedDeviceRegistry.describeReason(code))
        }
    }

    @Test
    fun store_reason_codes_match_the_windows_contract() {
        val codes = entries("storeReasonCodes").map { textOf(it) }
        assertEquals("storeReasonCodes 条数", 5, codes.size)
        assertEquals("storeReasonCodes 顺序", codes, PairedDeviceStoreReasons.ALL)
    }

    @Test
    fun state_labels_match_the_windows_contract() {
        val labels = root.getValue("stateLabels").jsonObject
        assertEquals("new", textOf(labels, "new"), PairedDeviceRegistry.describeState(PairedFingerprintState.NEW))
        assertEquals("match", textOf(labels, "match"), PairedDeviceRegistry.describeState(PairedFingerprintState.MATCH))
        assertEquals("mismatch", textOf(labels, "mismatch"), PairedDeviceRegistry.describeState(PairedFingerprintState.MISMATCH))
    }

    @Test
    fun describe_reason_cases_match_the_windows_contract() {
        for (entry in entries("describeReasonCases").map { it.jsonObject }) {
            val reason = nullableTextOf(entry, "reason")
            assertEquals(reason ?: "null", textOf(entry, "text"), PairedDeviceRegistry.describeReason(reason))
        }
    }

    // ------------------------------------------------------------ 构造

    @Test
    fun create_cases_match_the_windows_contract() {
        val produced = linkedMapOf(
            "trims-and-lowercases" to PairedDevice.create(" device-a ", fingerprintA.uppercase(), " 家庭 NAS ", frozenNow, 3),
            "truncates-long-name" to PairedDevice.create("device-b", fingerprintB, "x".repeat(200), frozenNow, 0),
            "negative-source-count" to PairedDevice.create("device-c", fingerprintC, "", frozenNow, -5),
            "keeps-explicit-paired-at" to PairedDevice.create("device-d", fingerprintA, null, frozenNow, 1, frozenNow - 5000),
        )
        for (entry in entries("createCases").map { it.jsonObject }) {
            val name = textOf(entry, "name")
            val expected = deviceOf(entry.getValue("device"))
            assertEquals(name, expected, produced[name])
        }
    }

    // ------------------------------------------------------------ 核对

    @Test
    fun verify_cases_match_the_windows_contract() {
        val registry = seededRegistry { }
        for (entry in entries("verifyCases").map { it.jsonObject }) {
            val name = textOf(entry, "name")
            val actual = registry.verify(nullableTextOf(entry, "deviceId"), nullableTextOf(entry, "fingerprint"))
            assertEquals(name + " (state)", textOf(entry, "state"), stateName(actual.state))
            assertEquals(name + " (reason)", textOf(entry, "reason"), actual.reason)
            assertEquals(name + " (presented)", textOf(entry, "presentedFingerprint"), actual.presentedFingerprint)
            assertEquals(name + " (pinned)", nullableTextOf(entry, "pinnedFingerprint"), actual.pinnedFingerprint)
            assertEquals(name + " (canPin)", boolOf(entry, "canPin"), actual.canPin)
        }
    }

    // ------------------------------------------------------------ 登记

    @Test
    fun register_cases_match_the_windows_contract() {
        val store = InMemoryPairedDeviceStore()
        val registry = PairedDeviceRegistry(store)
        for (entry in entries("registerCases").map { it.jsonObject }) {
            val name = textOf(entry, "name")
            if (textOf(entry, "kind") == "final") {
                val expectedIds = entry.getValue("deviceIds").jsonArray.map { textOf(it) }
                assertEquals(name + " (ids)", expectedIds, registry.getAll().map { it.deviceId })
                assertEquals(name + " (count)", textOf(entry, "count").toInt(), registry.count)
                assertEquals(name + " (isFull)", boolOf(entry, "isFull"), registry.isFull)
                continue
            }
            val observation = entry.getValue("observation").jsonObject
            val actual = registry.register(
                PairedDeviceObservation(
                    deviceId = textOf(observation, "deviceId"),
                    fingerprint = textOf(observation, "fingerprint"),
                    displayName = textOf(observation, "displayName"),
                    sourceCount = textOf(observation, "sourceCount").toInt(),
                    nowEpochMs = observation.getValue("nowEpochMs").jsonPrimitive.long,
                ),
            )
            assertEquals(name + " (accepted)", boolOf(entry, "accepted"), actual.accepted)
            assertEquals(name + " (added)", boolOf(entry, "added"), actual.added)
            assertEquals(name + " (state)", textOf(entry, "state"), stateName(actual.state))
            assertEquals(name + " (reason)", textOf(entry, "reason"), actual.reason)
            val expectedDevice = entry["device"]
            if (expectedDevice == null || expectedDevice is JsonNull) {
                assertNull(name + " (device)", actual.device)
            } else {
                assertEquals(name + " (device)", deviceOf(expectedDevice), actual.device)
            }
        }
    }

    @Test
    fun capacity_cases_match_the_windows_contract() {
        val store = InMemoryPairedDeviceStore()
        val registry = PairedDeviceRegistry(store, capacity = 2)
        val cases = entries("capacityCases").map { it.jsonObject }
        for (entry in cases) {
            val name = textOf(entry, "name")
            when (textOf(entry, "kind")) {
                "register" -> {
                    val observation = entry.getValue("observation").jsonObject
                    val actual = registry.register(
                        PairedDeviceObservation(
                            deviceId = textOf(observation, "deviceId"),
                            fingerprint = textOf(observation, "fingerprint"),
                            displayName = textOf(observation, "displayName"),
                            sourceCount = textOf(observation, "sourceCount").toInt(),
                            nowEpochMs = observation.getValue("nowEpochMs").jsonPrimitive.long,
                        ),
                    )
                    assertEquals(name + " (accepted)", boolOf(entry, "accepted"), actual.accepted)
                    assertEquals(name + " (added)", boolOf(entry, "added"), actual.added)
                    assertEquals(name + " (state)", textOf(entry, "state"), stateName(actual.state))
                    assertEquals(name + " (reason)", textOf(entry, "reason"), actual.reason)
                }
                "full" -> assertEquals(name, boolOf(entry, "isFull"), registry.isFull)
                "forget" -> assertEquals(name, boolOf(entry, "result"), registry.forget(textOf(entry, "deviceId")))
                "final" -> {
                    val expectedIds = entry.getValue("deviceIds").jsonArray.map { textOf(it) }
                    assertEquals(name, expectedIds, registry.getAll().map { it.deviceId })
                }
                else -> error("未知用例类型")
            }
        }
    }

    @Test
    fun forget_cases_match_the_windows_contract() {
        var store: InMemoryPairedDeviceStore? = null
        val registry = seededRegistry { store = it }
        for (entry in entries("forgetCases").map { it.jsonObject }) {
            val name = textOf(entry, "name")
            when (textOf(entry, "kind")) {
                "forget" -> {
                    val deviceId = nullableTextOf(entry, "deviceId")
                    assertEquals(name, boolOf(entry, "result"), registry.forget(deviceId))
                }
                "verify" -> {
                    val verification = registry.verify("device-a", fingerprintA)
                    assertEquals(name + " (state)", textOf(entry, "state"), stateName(verification.state))
                    assertEquals(name + " (reason)", textOf(entry, "reason"), verification.reason)
                }
                "final" -> {
                    val expectedIds = entry.getValue("deviceIds").jsonArray.map { textOf(it) }
                    assertEquals(name + " (ids)", expectedIds, registry.getAll().map { it.deviceId })
                    assertEquals(name + " (writeCount)", textOf(entry, "writeCount").toInt(), store!!.writeCount)
                }
                else -> error("未知用例类型")
            }
        }
    }

    // ------------------------------------------------------------ 摘要

    @Test
    fun describe_cases_match_the_windows_contract() {
        for (entry in entries("describeCases").map { it.jsonObject }) {
            val name = textOf(entry, "name")
            val device = deviceOf(entry.getValue("device"))
            val now = entry.getValue("nowEpochMs").jsonPrimitive.long
            val actual = device.describe(now)
            assertEquals(name + " (describe via registry)", actual, PairedDeviceRegistry.describe(device, now))
            val expected = nullableTextOf(entry, "describe")
            if (expected != null) {
                assertEquals(name + " (describe)", expected, actual)
            }
            val suffix = nullableTextOf(entry, "describeSuffix")
            if (suffix != null) {
                // 时钟偏移提示位于尾部「 · 来源 N 个」之前
                assertTrue(name + " (suffix)", actual.contains(suffix))
            }
            val displayNameOrId = nullableTextOf(entry, "displayNameOrId")
            if (displayNameOrId != null) {
                assertEquals(name + " (displayNameOrId)", displayNameOrId, device.displayNameOrId)
                assertEquals(name + " (fingerprintDisplay)", textOf(entry, "fingerprintDisplay"), device.fingerprintDisplay)
            }
        }
    }

    // ------------------------------------------------------------ 清单

    @Test
    fun presenter_cases_match_the_windows_contract() {
        val produced = linkedMapOf<String, PairedDeviceListView>(
            "empty" to PairedDeviceListPresenter.build(emptyList(), PairedDeviceRegistry.DEFAULT_CAPACITY, frozenNow),
            "two-devices" to PairedDeviceListPresenter.build(
                listOf(
                    PairedDevice.create("device-android-01", fingerprintA, "家庭 NAS（客厅）", frozenNow, 3, pairedAtEpochMs = 0L),
                    PairedDevice.create("abc", fingerprintB, "", frozenNow, 0, pairedAtEpochMs = 0L),
                ),
                PairedDeviceRegistry.DEFAULT_CAPACITY,
                frozenNow,
            ),
            "full-at-two" to PairedDeviceListPresenter.build(
                listOf(
                    PairedDevice.create("device-a", fingerprintA, "", frozenNow, 0, pairedAtEpochMs = 0L),
                    PairedDevice.create("device-b", fingerprintB, "", frozenNow, 0, pairedAtEpochMs = 0L),
                ),
                2,
                frozenNow,
            ),
            "null-is-empty" to PairedDeviceListPresenter.build(null, 0, frozenNow),
        )
        for (entry in entries("presenterCases").map { it.jsonObject }) {
            val name = textOf(entry, "name")
            val actual = requireNotNull(produced[name]) { "未构造用例 " + name }
            assertEquals(name + " (countLabel)", textOf(entry, "countLabel"), actual.countLabel)
            assertEquals(name + " (emptyLabel)", textOf(entry, "emptyLabel"), actual.emptyLabel)
            assertEquals(name + " (tofuLabel)", textOf(entry, "tofuLabel"), actual.tofuLabel)
            assertEquals(name + " (capacityLabel)", textOf(entry, "capacityLabel"), actual.capacityLabel)
            assertEquals(name + " (isEmpty)", boolOf(entry, "isEmpty"), actual.isEmpty)
            assertEquals(name + " (isFull)", boolOf(entry, "isFull"), actual.isFull)
            assertEquals(name + " (count)", textOf(entry, "count").toInt(), actual.count)
            assertEquals(name + " (capacity)", textOf(entry, "capacity").toInt(), actual.capacity)
            val expectedRows = entry.getValue("rows").jsonArray.map { it.jsonObject }
            assertEquals(name + " (row count)", expectedRows.size, actual.rows.size)
            for (index in expectedRows.indices) {
                val expected = expectedRows[index]
                val row = actual.rows[index]
                val label = name + " row " + index
                assertEquals(label + " (deviceId)", textOf(expected, "deviceId"), row.deviceId)
                assertEquals(label + " (displayName)", textOf(expected, "displayName"), row.displayName)
                assertEquals(label + " (fingerprintLabel)", textOf(expected, "fingerprintLabel"), row.fingerprintLabel)
                assertEquals(label + " (pairedLabel)", textOf(expected, "pairedLabel"), row.pairedLabel)
                assertEquals(label + " (lastSeenLabel)", textOf(expected, "lastSeenLabel"), row.lastSeenLabel)
                assertEquals(label + " (sourceLabel)", textOf(expected, "sourceLabel"), row.sourceLabel)
                assertEquals(label + " (summaryLine)", textOf(expected, "summaryLine"), row.summaryLine)
                assertEquals(label + " (detailLine)", textOf(expected, "detailLine"), row.detailLine)
                assertEquals(label + " (forgetLabel)", textOf(expected, "forgetLabel"), row.forgetLabel)
                assertEquals(label + " (lastSeenEpochMs)", expected.getValue("lastSeenEpochMs").jsonPrimitive.long, row.lastSeenEpochMs)
            }
        }
    }

    // ------------------------------------------------------------ 磁盘字节

    @Test
    fun document_cases_are_written_byte_identically() {
        for (entry in entries("documentCases").map { it.jsonObject }) {
            val name = textOf(entry, "name")
            val devices = entry.getValue("devices").jsonArray.map { deviceOf(it) }
            withTempDirectory { directory ->
                val store = FilePairedDeviceStore(directory)
                if (devices.isEmpty()) {
                    store.save(PairedDevice.create("placeholder", fingerprintA, "", frozenNow, 0))
                    store.forget("placeholder")
                } else {
                    for (device in devices) {
                        store.save(device)
                    }
                }
                val bytes = File(store.filePath).readBytes()
                assertEquals(name + " (byte length)", textOf(entry, "utf8ByteLength").toInt(), bytes.size)
                assertEquals(name + " (sha256)", textOf(entry, "sha256"), sha256(bytes))
                assertEquals(name + " (base64)", textOf(entry, "base64"), Base64.getEncoder().encodeToString(bytes))
                // 写入按插入顺序，读回按规范顺序（deviceId 先 OrdinalIgnoreCase 再 Ordinal）
                val canonical = devices.sortedWith(
                    compareBy<PairedDevice, String>(String.CASE_INSENSITIVE_ORDER) { device -> device.deviceId }
                        .thenBy { device -> device.deviceId },
                )
                val reopened = FilePairedDeviceStore(directory)
                assertEquals(name + " (round trip)", canonical, reopened.getAll())
                assertNull(name + " (loadError)", reopened.loadError)
            }
        }
    }

    @Test
    fun load_cases_match_the_windows_contract() {
        for (entry in entries("loadCases").map { it.jsonObject }) {
            val name = textOf(entry, "name")
            withTempDirectory { directory ->
                File(directory, FilePairedDeviceStore.FILE_NAME)
                    .writeText(textOf(entry, "text"), Charsets.UTF_8)
                val store = FilePairedDeviceStore(directory)
                val devices = store.getAll()
                assertEquals(name + " (loadError)", nullableTextOf(entry, "loadError"), store.loadError)
                assertEquals(name + " (count)", textOf(entry, "deviceCount").toInt(), devices.size)
                val expected = entry.getValue("devices").jsonArray.map { deviceOf(it) }
                assertEquals(name + " (devices)", expected, devices)
            }
        }
    }

    @Test
    fun write_rejects_match_the_windows_contract() {
        for (entry in entries("writeRejects").map { it.jsonObject }) {
            val name = textOf(entry, "name")
            var outcome = "accepted"
            withTempDirectory { directory ->
                try {
                    when (name) {
                        "blank-identity" -> FilePairedDeviceStore(directory)
                            .save(PairedDevice.create("device-a", fingerprintA, "", frozenNow, 0).copy(deviceId = "   "))
                        "capacity-limit" -> {
                            val store = FilePairedDeviceStore(directory, capacity = 1)
                            store.save(PairedDevice.create("device-a", fingerprintA, "", frozenNow, 0))
                            store.save(PairedDevice.create("device-b", fingerprintB, "", frozenNow, 0))
                        }
                    }
                } catch (exception: IllegalArgumentException) {
                    outcome = "argument-exception"
                } catch (exception: IOException) {
                    outcome = exception.message ?: ""
                }
            }
            assertEquals(name, textOf(entry, "outcome"), outcome)
        }
    }

    private companion object {
        const val RESOURCE_PATH: String = "/pairing/paired-device-vectors.json"
        const val HEX_LOWER: String = "0123456789abcdef"
    }
}
