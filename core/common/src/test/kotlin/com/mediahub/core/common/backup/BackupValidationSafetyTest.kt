package com.mediahub.core.common.backup

import org.junit.Assert.*
import org.junit.Test

/** Authenticated malformed input is constructed independently of the production exporter. */
internal fun uncheckedBackup(payload: BackupDtos.BackupPayload, password: CharArray): ByteArray {
    val salt = BackupCrypto.randomSalt()
    val key = BackupCrypto.deriveKey(password, salt, 10_000, 256)
    val nonce = BackupCrypto.randomNonce()
    val aad = BackupCrypto.aadFor("MHBK", 1, key.params.algorithm, 10_000)
    val encrypted = BackupCrypto.encrypt(BackupDtos.encodePayload(payload).toByteArray(), key.key, nonce, aad)
    return BackupFileFormat.encodeEnvelope(BackupFileFormat.Envelope(
        "MHBK", 1, key.params.algorithm, BackupFileFormat.encodeB64(salt), 10_000, 256,
        BackupFileFormat.encodeB64(nonce), BackupFileFormat.encodeB64(encrypted),
    )).toByteArray()
}

class BackupValidationSafetyTest {
    private val password = "fixture-password".toCharArray()
    private fun server(id: String = "server") = BackupDtos.ServerDto(
        id, "Fixture", "EMBY", endpoints = listOf(
            BackupDtos.EndpointDto("Main", "https://media.example", true, true, 0),
        ),
    )
    private fun payload() = BackupDtos.BackupPayload(
        BackupFileFormat.Manifest(1, 1, "fixture-version", 1, listOf("servers", "progress", "preferences"),
            mapOf("servers" to 1, "progress" to 0, "preferences" to 1)),
        servers = listOf(server()), preferences = BackupDtos.PreferencesDto(),
    )
    private fun imported(payload: BackupDtos.BackupPayload) =
        BackupSerializer.import(uncheckedBackup(payload, password), password)

    @Test fun `encoded credential query names cannot bypass guard`() {
        for (name in listOf("%61pi_key", "access%5Ftoken", "Cookie", "PlaySessionId", "X-Amz-Credential")) {
            assertNotNull("credential parameter rejected: $name", BackupUrlGuard.inspect("https://media.example/?$name=fixture-secret"))
        }
    }

    @Test fun `invalid authority is rejected and query values retain case in identity`() {
        for (url in listOf("https:///missing-host", "https://", "https://host:invalid", "https://host:99999")) {
            assertNotNull(url, BackupUrlGuard.inspect(url))
        }
        assertNotEquals(BackupUrlGuard.normalizeForIdentity("https://HOST?account=A"),
            BackupUrlGuard.normalizeForIdentity("https://host?account=a"))
        assertEquals("https://host/path", BackupUrlGuard.normalizeForIdentity("HTTPS://HOST/path/?#ignored"))
    }

    @Test fun `local server without endpoints can round trip`() {
        val local = payload().copy(servers = listOf(server().copy(type = "LOCAL", endpoints = emptyList())))
        assertTrue(BackupSerializer.import(BackupSerializer.export(local, password), password) is BackupSerializer.ImportResult.Ok)
    }

    @Test fun `missing counts and undeclared populated sections are rejected`() {
        val original = payload()
        val manifests = listOf(
            original.manifest.copy(recordCounts = emptyMap()),
            original.manifest.copy(includedSections = emptyList(), recordCounts = emptyMap()),
            original.manifest.copy(includedSections = listOf("servers"), recordCounts = mapOf("servers" to 1)),
        )
        manifests.forEach { assertTrue(imported(original.copy(manifest = it)) is BackupSerializer.ImportResult.Corrupted) }
    }

    @Test fun `invalid preference values are rejected before restore`() {
        val original = payload()
        val prefs = original.preferences!!
        val invalid = listOf(
            prefs.copy(defaultPlaybackSpeed = 0f), prefs.copy(subtitleSizeSp = -1),
            prefs.copy(maxBitrateBps = -1), prefs.copy(subtitleStyle = prefs.subtitleStyle.copy(edgeType = 99)),
            prefs.copy(subtitleStyle = prefs.subtitleStyle.copy(textScale = -1f)),
            prefs.copy(subtitleStyle = prefs.subtitleStyle.copy(bottomPaddingFraction = 2f)),
            prefs.copy(gestures = prefs.gestures.copy(doubleTapSeekForwardSeconds = -1)),
            prefs.copy(gestures = prefs.gestures.copy(longPressDefaultSpeed = 9f)),
        )
        invalid.forEach { assertTrue("Invalid preferences accepted: $it", imported(original.copy(preferences = it)) is BackupSerializer.ImportResult.Corrupted) }
    }

    @Test fun `distinct composite progress keys containing slash are not duplicates`() {
        val original = payload()
        val progress = listOf(
            BackupDtos.ProgressDto("a/b", "c", 1, 2, true, 1),
            BackupDtos.ProgressDto("a", "b/c", 1, 2, true, 1),
        )
        val updated = original.copy(progress = progress,
            manifest = original.manifest.copy(recordCounts = original.manifest.recordCounts + ("progress" to 2)))
        assertTrue(imported(updated) is BackupSerializer.ImportResult.Ok)
    }

    @Test fun `untrusted fields are not echoed in validation errors`() {
        val marker = "fixture-sensitive-marker"
        val original = payload()
        val duplicate = server(marker)
        val invalid = original.copy(servers = listOf(duplicate, duplicate),
            manifest = original.manifest.copy(recordCounts = original.manifest.recordCounts + ("servers" to 2)))
        val result = imported(invalid)
        assertTrue(result is BackupSerializer.ImportResult.Corrupted)
        assertFalse(result.toString().contains(marker))
    }

    @Test fun `export refuses a payload its importer cannot accept`() {
        val invalid = payload().copy(servers = listOf(server().copy(endpoints = emptyList())))
        assertThrows(IllegalArgumentException::class.java) { BackupSerializer.export(invalid, password) }
    }

    @Test fun `extreme subtitle scale cannot reach the live renderer`() {
        val original = payload()
        for (scale in listOf(Float.MAX_VALUE, 0.1f, 2.01f)) {
            val invalid = original.copy(preferences = original.preferences!!.copy(
                subtitleStyle = original.preferences.subtitleStyle.copy(textScale = scale)))
            assertTrue(imported(invalid) is BackupSerializer.ImportResult.Corrupted)
        }
    }

    @Test fun `record and endpoint limits are enforced before export and after authentication`() {
        val original = payload()
        val manyServers = original.copy(servers = List(BackupSerializer.MAX_SERVERS + 1) { server("s-$it") },
            manifest = original.manifest.copy(recordCounts = original.manifest.recordCounts + ("servers" to BackupSerializer.MAX_SERVERS + 1)))
        val manyEndpoints = original.copy(servers = listOf(server().copy(endpoints = List(BackupSerializer.MAX_ENDPOINTS_PER_SERVER + 1) {
            server().endpoints.single().copy(name = "ep-$it")
        })))
        val records = List(BackupSerializer.MAX_PROGRESS_RECORDS + 1) { BackupDtos.ProgressDto("server", "item-$it", 0, 1, true, 1) }
        val manyProgress = original.copy(progress = records,
            manifest = original.manifest.copy(recordCounts = original.manifest.recordCounts + ("progress" to records.size)))
        for (invalid in listOf(manyServers, manyEndpoints, manyProgress)) {
            assertThrows(IllegalArgumentException::class.java) { BackupSerializer.export(invalid, password) }
            assertTrue(imported(invalid) is BackupSerializer.ImportResult.Corrupted)
        }
    }
}
