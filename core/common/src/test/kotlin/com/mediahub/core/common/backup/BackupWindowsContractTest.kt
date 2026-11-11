package com.mediahub.core.common.backup

import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与 Windows `MediaHub.Tests.BackupCrossPlatformVectorTests` 共用一份向量文件（W15）。
 *
 * Windows 侧是权威实现：它写出 `.mhbackup` 容器与固定 salt/nonce 的密文，本测试必须解的出一模一样的字节。
 * 反向证据（Android 编码、Windows 解码）写到 `androidExport`，由生成器合入同一文件后两边一起断言。
 */
class BackupWindowsContractTest {

    private val root: JsonObject = run {
        val stream = BackupWindowsContractTest::class.java.getResourceAsStream("/backup/backup-vectors.json")
        checkNotNull(stream) { "missing test resource /backup/backup-vectors.json" }
        Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject
    }

    // ------------------------------------------------------------- helpers

    private fun JsonObject.text(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.number(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()
    private fun JsonObject.longNumber(key: String): Long = this[key]!!.jsonPrimitive.content.toLong()
    private fun JsonObject.flag(key: String): Boolean = this[key]!!.jsonPrimitive.content.toBoolean()
    private fun JsonObject.decimal(key: String): Double = this[key]!!.jsonPrimitive.content.toDouble()

    private fun b64(text: String): ByteArray = Base64.getDecoder().decode(text)
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun statusName(result: BackupSerializer.ImportResult): String = when (result) {
        is BackupSerializer.ImportResult.Ok -> "ok"
        is BackupSerializer.ImportResult.AuthenticationFailed -> "authenticationFailed"
        is BackupSerializer.ImportResult.Corrupted -> "corrupted"
        is BackupSerializer.ImportResult.VersionTooNew -> "versionTooNew"
        BackupSerializer.ImportResult.NotABackupFile -> "notABackup"
    }

    private fun case(id: String): JsonObject =
        root["cases"]!!.jsonArray.map { it.jsonObject }.first { it.text("id") == id }

    private fun importCase(c: JsonObject): BackupSerializer.ImportResult {
        val bytes = c["envelopeJson"]?.jsonPrimitive?.content?.toByteArray(Charsets.UTF_8)
            ?: c.text("text").toByteArray(Charsets.UTF_8)
        return BackupSerializer.import(bytes, c.text("password").toCharArray())
    }

    // ------------------------------------------------------------- crypto

    @Test
    fun `the windows crypto case reproduces the exact ciphertext from the same password salt and nonce`() {
        val c = root["cryptoCase"]!!.jsonObject
        val iterations = c.number("iterations")
        val salt = b64(c.text("saltB64"))
        val nonce = b64(c.text("nonceB64"))
        val derived = BackupCrypto.deriveKey(c.text("password").toCharArray(), salt, iterations, c.number("keyLengthBits"))
        val aad = BackupCrypto.aadFor(BackupCrypto.MAGIC, c.number("formatVersion"), c.text("kdfAlgorithm"), iterations)

        assertEquals(c.text("aadUtf8"), aad.decodeToString())
        assertEquals(BackupCrypto.MAGIC, c.text("aadUtf8").split("|")[0])
        assertEquals(BackupCrypto.FORMAT_VERSION, c.number("formatVersion"))
        assertEquals(BackupCrypto.PBKDF2_ITERATIONS, iterations)
        assertEquals(BackupCrypto.PBKDF2_KEY_BITS, c.number("keyLengthBits"))

        val plaintext = c.text("plaintextJson").toByteArray(Charsets.UTF_8)
        val ciphertext = BackupCrypto.encrypt(plaintext, derived.key, nonce, aad)
        assertEquals(c.text("ciphertextB64"), b64(ciphertext))
        assertEquals(c.text("plaintextJson"), BackupCrypto.decrypt(ciphertext, derived.key, nonce, aad).decodeToString())
    }

    // ------------------------------------------------------------- container cases

    @Test
    fun `every container case lands in the classification the windows side recorded`() {
        val cases = root["cases"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        for (element in cases) {
            val c = element.jsonObject
            val id = c.text("id")
            val result = importCase(c)
            assertEquals(id, c.text("expectedStatus"), statusName(result))
            if (result is BackupSerializer.ImportResult.VersionTooNew) {
                assertEquals(id, c.number("expectedFormatVersion"), result.fileVersion)
            }
            if (result is BackupSerializer.ImportResult.Corrupted) {
                assertTrue(id + ": reason must not be blank", result.reason.isNotBlank())
            }
        }
    }

    @Test
    fun `the ok container carries every whitelisted field the windows side pins`() {
        val result = importCase(case("windows-export-ok"))
        assertTrue(result is BackupSerializer.ImportResult.Ok)
        assertPayload(
            (result as BackupSerializer.ImportResult.Ok).payload,
            root["expectations"]!!.jsonObject,
        )
    }

    @Test
    fun `the fixed salt container is accepted so both sides share one deterministic vector`() {
        val result = importCase(case("fixed-salt-ok"))
        assertTrue(result is BackupSerializer.ImportResult.Ok)
        assertEquals(
            root["expectations"]!!.jsonObject["server"]!!.jsonObject.text("backupId"),
            (result as BackupSerializer.ImportResult.Ok).payload.servers[0].backupId,
        )
    }

    // ------------------------------------------------------------- reverse direction

    @Test
    fun `the reverse vector written here is accepted by this side too`() {
        val android = root["androidExport"]?.jsonObject
        assertNotNull("androidExport missing: run the writer test with MH_BACKUP_VECTOR_OUT set and merge", android)
        val a = android!!
        val result = BackupSerializer.import(
            a.text("envelopeJson").toByteArray(Charsets.UTF_8),
            a.text("password").toCharArray(),
        )
        assertTrue(result is BackupSerializer.ImportResult.Ok)
        assertPayload((result as BackupSerializer.ImportResult.Ok).payload, a["expect"]!!.jsonObject)

        val salt = b64(a.text("saltB64"))
        val nonce = b64(a.text("nonceB64"))
        val derived = BackupCrypto.deriveKey(
            a.text("password").toCharArray(), salt, a.number("iterations"), BackupCrypto.PBKDF2_KEY_BITS,
        )
        val aad = BackupCrypto.aadFor(
            BackupCrypto.MAGIC, BackupCrypto.FORMAT_VERSION, "PBKDF2WithHmacSHA256", a.number("iterations"),
        )
        val recomputed = BackupCrypto.encrypt(a.text("payloadJson").toByteArray(Charsets.UTF_8), derived.key, nonce, aad)
        assertEquals(a.text("ciphertextB64"), b64(recomputed))
    }

    /**
     * 反向证据的写入口：`MH_BACKUP_VECTOR_OUT` 指向仓库外的文件时，把 Android 编码 + 加密的容器写出去，
     * 由向量生成器合入 `backup-vectors.json` 的 `androidExport`，交给 Windows 侧解。未设置时不做任何事。
     */
    @Test
    fun `when asked this test writes the android export vector for the windows suite`() {
        val outPath = System.getenv("MH_BACKUP_VECTOR_OUT") ?: return
        val payload = androidPayload()
        val payloadJson = BackupDtos.encodePayload(payload)
        val salt = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { it.toByte() }
        val derived = BackupCrypto.deriveKey(
            PASSWORD.toCharArray(), salt, BackupCrypto.PBKDF2_ITERATIONS, BackupCrypto.PBKDF2_KEY_BITS,
        )
        val aad = BackupCrypto.aadFor(
            BackupCrypto.MAGIC, BackupCrypto.FORMAT_VERSION, "PBKDF2WithHmacSHA256", BackupCrypto.PBKDF2_ITERATIONS,
        )
        val ciphertext = BackupCrypto.encrypt(payloadJson.toByteArray(Charsets.UTF_8), derived.key, nonce, aad)
        val envelope = BackupFileFormat.Envelope(
            BackupCrypto.MAGIC,
            BackupCrypto.FORMAT_VERSION,
            "PBKDF2WithHmacSHA256",
            b64(salt),
            BackupCrypto.PBKDF2_ITERATIONS,
            BackupCrypto.PBKDF2_KEY_BITS,
            b64(nonce),
            b64(ciphertext),
        )
        val envelopeJson = BackupFileFormat.encodeEnvelope(envelope)

        // 自检：本端读取器先接受自己写出的容器，否则窗口侧的失败会无从归因。
        val selfCheck = BackupSerializer.import(envelopeJson.toByteArray(Charsets.UTF_8), PASSWORD.toCharArray())
        assertTrue(selfCheck is BackupSerializer.ImportResult.Ok)

        val doc = buildJsonObject {
            put("password", PASSWORD)
            put("envelopeJson", envelopeJson)
            put("payloadJson", payloadJson)
            put("saltB64", b64(salt))
            put("nonceB64", b64(nonce))
            put("ciphertextB64", b64(ciphertext))
            put("iterations", BackupCrypto.PBKDF2_ITERATIONS)
            put("expect", expectationsFor(payload))
        }
        File(outPath).writeText(doc.toString() + "\n")
    }

    // ------------------------------------------------------------- fixtures

    private fun androidPayload() = BackupDtos.BackupPayload(
        manifest = BackupFileFormat.Manifest(
            BackupCrypto.FORMAT_VERSION,
            BackupCrypto.MINIMUM_READER_VERSION,
            "0.1.0-alpha.1",
            1_700_000_000_000L,
            listOf("servers", "progress", "preferences", "favorites"),
            mapOf("servers" to 1, "progress" to 1, "preferences" to 1, "favorites" to 1),
        ),
        servers = listOf(
            BackupDtos.ServerDto(
                backupId = "srv-1",
                name = "Test Emby",
                type = "EMBY",
                username = "user-A",
                isDefault = true,
                sortOrder = 0,
                endpoints = listOf(
                    BackupDtos.EndpointDto(
                        name = "默认线路",
                        url = "https://media.example:8920",
                        isPrimary = true,
                        enabled = true,
                        sortOrder = 0,
                    ),
                ),
            ),
        ),
        progress = listOf(
            BackupDtos.ProgressDto(
                serverBackupId = "srv-1",
                itemId = "item-1",
                positionMs = 90_000,
                durationMs = 600_000,
                isPaused = false,
                updatedAtEpochMs = 1_700_000_000_000L,
                itemTitle = "Test Item",
                itemType = "Movie",
            ),
        ),
        favorites = listOf(BackupDtos.FavoriteDto("srv-1", "item-1", "Test Item", 1_700_000_100_000L)),
        preferences = BackupDtos.PreferencesDto(
            defaultPlaybackSpeed = 1.5f,
            subtitleSizeSp = 20,
            autoPlayNextEpisode = false,
        ),
    )

    private fun expectationsFor(payload: BackupDtos.BackupPayload): JsonObject = buildJsonObject {
        put("manifestAppVersion", payload.manifest.appVersion)
        put("manifestCreatedAtEpochMs", payload.manifest.createdAtEpochMs)
        putJsonArray("includedSections") { payload.manifest.includedSections.forEach { add(it) } }
        putJsonObject("recordCounts") { payload.manifest.recordCounts.forEach { (k, v) -> put(k, v) } }
        putJsonObject("server") {
            val srv = payload.servers[0]
            put("backupId", srv.backupId)
            put("name", srv.name)
            put("type", srv.type)
            put("username", srv.username)
            put("isDefault", srv.isDefault)
            put("sortOrder", srv.sortOrder)
            put("endpointCount", srv.endpoints.size)
            put("primaryUrl", srv.endpoints.first { it.isPrimary }.url)
        }
        putJsonObject("progress") {
            val p = payload.progress[0]
            put("serverBackupId", p.serverBackupId)
            put("itemId", p.itemId)
            put("positionMs", p.positionMs)
            put("durationMs", p.durationMs)
            put("isPaused", p.isPaused)
            put("updatedAtEpochMs", p.updatedAtEpochMs)
            put("itemTitle", p.itemTitle)
            put("itemType", p.itemType)
        }
        putJsonObject("favorite") {
            val f = payload.favorites[0]
            put("serverBackupId", f.serverBackupId)
            put("itemId", f.itemId)
            put("title", f.title)
            put("addedAtEpochMs", f.addedAtEpochMs)
        }
        putJsonObject("preferences") {
            val pref = payload.preferences!!
            put("defaultPlaybackSpeed", pref.defaultPlaybackSpeed.toDouble())
            put("subtitleSizeSp", pref.subtitleSizeSp)
            put("autoPlayNextEpisode", pref.autoPlayNextEpisode)
        }
    }

    private fun assertPayload(payload: BackupDtos.BackupPayload, e: JsonObject) {
        assertEquals(e.text("manifestAppVersion"), payload.manifest.appVersion)
        assertEquals(e.longNumber("manifestCreatedAtEpochMs"), payload.manifest.createdAtEpochMs)
        assertEquals(
            e["includedSections"]!!.jsonArray.map { it.jsonPrimitive.content },
            payload.manifest.includedSections,
        )
        val counts = e["recordCounts"]!!.jsonObject
        assertEquals(counts.size, payload.manifest.recordCounts.size)
        counts.forEach { (k, v) -> assertEquals(k, v.jsonPrimitive.content.toInt(), payload.manifest.recordCounts[k]) }

        val server = e["server"]!!.jsonObject
        assertEquals(1, payload.servers.size)
        val srv = payload.servers[0]
        assertEquals(server.text("backupId"), srv.backupId)
        assertEquals(server.text("name"), srv.name)
        assertEquals(server.text("type"), srv.type)
        assertEquals(server.text("username"), srv.username)
        assertEquals(server.flag("isDefault"), srv.isDefault)
        assertEquals(server.number("sortOrder"), srv.sortOrder)
        assertEquals(server.number("endpointCount"), srv.endpoints.size)
        assertEquals(server.text("primaryUrl"), srv.endpoints.first { it.isPrimary }.url)

        val progress = e["progress"]!!.jsonObject
        assertEquals(1, payload.progress.size)
        val p = payload.progress[0]
        assertEquals(progress.text("serverBackupId"), p.serverBackupId)
        assertEquals(progress.text("itemId"), p.itemId)
        assertEquals(progress.longNumber("positionMs"), p.positionMs)
        assertEquals(progress.longNumber("durationMs"), p.durationMs)
        assertEquals(progress.flag("isPaused"), p.isPaused)
        assertEquals(progress.longNumber("updatedAtEpochMs"), p.updatedAtEpochMs)
        assertEquals(progress.text("itemTitle"), p.itemTitle)
        assertEquals(progress.text("itemType"), p.itemType)

        val favorite = e["favorite"]!!.jsonObject
        assertEquals(1, payload.favorites.size)
        val f = payload.favorites[0]
        assertEquals(favorite.text("serverBackupId"), f.serverBackupId)
        assertEquals(favorite.text("itemId"), f.itemId)
        assertEquals(favorite.text("title"), f.title)
        assertEquals(favorite.longNumber("addedAtEpochMs"), f.addedAtEpochMs)

        val prefs = e["preferences"]!!.jsonObject
        val pref = payload.preferences!!
        assertEquals(prefs.decimal("defaultPlaybackSpeed"), pref.defaultPlaybackSpeed.toDouble(), 0.000001)
        assertEquals(prefs.number("subtitleSizeSp"), pref.subtitleSizeSp)
        assertEquals(prefs.flag("autoPlayNextEpisode"), pref.autoPlayNextEpisode)
    }

    private companion object {
        const val PASSWORD = "跨平台备份-测试口令-2026"
    }
}
