package com.mediahub.provider.base

import com.mediahub.core.security.SecretStorage
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.SessionCredential
import java.nio.charset.StandardCharsets
import java.util.Base64

/** CredentialVault 的加密实现；序列化后的内容仍只能写入 [SecretStorage]。 */
class EncryptedCredentialVault(
    private val storage: SecretStorage,
) : CredentialVault {

    override suspend fun savePending(serverId: String, credentials: Credentials) {
        storage.put(pendingKey(serverId), CredentialCodec.encode(credentials))
    }

    override suspend fun readPending(serverId: String): Credentials? =
        storage.get(pendingKey(serverId))?.let(CredentialCodec::decodePending)

    override suspend fun saveSession(serverId: String, session: SessionCredential) {
        storage.put(sessionKey(serverId), CredentialCodec.encode(session))
    }

    override suspend fun readSession(serverId: String): SessionCredential? =
        storage.get(sessionKey(serverId))?.let(CredentialCodec::decodeSession)

    override suspend fun clearPending(serverId: String) {
        storage.remove(pendingKey(serverId))
    }

    override suspend fun clear(serverId: String) {
        storage.remove(pendingKey(serverId))
        storage.remove(sessionKey(serverId))
    }

    private fun pendingKey(serverId: String) = "credential:pending:$serverId"
    private fun sessionKey(serverId: String) = "credential:session:$serverId"
}

/** 只负责可逆编码；安全性来自外层 SecretStorage 的 Android Keystore AES/GCM。 */
internal object CredentialCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(value: Credentials): String = when (value) {
        Credentials.Anonymous -> "P_ANON"
        is Credentials.UsernamePassword -> record("P_UP", value.username, value.password)
        is Credentials.BasicAuth -> record("P_BASIC", value.username, value.password)
        is Credentials.BearerToken -> record("P_BEARER", value.token)
        is Credentials.ApiKey -> record("P_API", value.apiKey)
        is Credentials.OAuth2 -> record(
            "P_OAUTH2",
            value.authorizationCode,
            value.codeVerifier,
            value.redirectUri,
        )
        is Credentials.DeviceCode -> record("P_DEVICE", value.deviceCode)
        is Credentials.RefreshToken -> record("P_REFRESH", value.refreshToken)
        is Credentials.CookieSession -> record("P_COOKIE", encodeMap(value.cookies))
        is Credentials.QrLogin -> record("P_QR", value.payload)
    }

    fun encode(value: SessionCredential): String = when (value) {
        is SessionCredential.AccessToken -> record(
            "S_ACCESS",
            value.accessToken,
            value.refreshToken,
            value.expiresAtEpochMs?.toString(),
        )
        is SessionCredential.BasicAuth -> record("S_BASIC", value.username, value.password)
        is SessionCredential.ApiKey -> record("S_API", value.apiKey)
        is SessionCredential.OAuth2 -> record(
            "S_OAUTH2",
            value.accessToken,
            value.refreshToken,
            value.expiresAtEpochMs?.toString(),
        )
        is SessionCredential.CookieSession -> record(
            "S_COOKIE",
            encodeMap(value.cookies),
            value.expiresAtEpochMs?.toString(),
        )
    }

    fun decodePending(raw: String): Credentials? = decode(raw) { tag, fields ->
        when (tag) {
            "P_ANON" -> Credentials.Anonymous
            "P_UP" -> Credentials.UsernamePassword(fields.required(0), fields.required(1))
            "P_BASIC" -> Credentials.BasicAuth(fields.required(0), fields.required(1))
            "P_BEARER" -> Credentials.BearerToken(fields.required(0))
            "P_API" -> Credentials.ApiKey(fields.required(0))
            "P_OAUTH2" -> Credentials.OAuth2(fields.required(0), fields.getOrNull(1), fields.getOrNull(2))
            "P_DEVICE" -> Credentials.DeviceCode(fields.required(0))
            "P_REFRESH" -> Credentials.RefreshToken(fields.required(0))
            "P_COOKIE" -> Credentials.CookieSession(decodeMap(fields.required(0)))
            "P_QR" -> Credentials.QrLogin(fields.required(0))
            else -> null
        }
    }

    fun decodeSession(raw: String): SessionCredential? = decode(raw) { tag, fields ->
        when (tag) {
            "S_ACCESS" -> SessionCredential.AccessToken(
                accessToken = fields.required(0),
                refreshToken = fields.getOrNull(1),
                expiresAtEpochMs = fields.getOrNull(2)?.toLongOrNull(),
            )
            "S_BASIC" -> SessionCredential.BasicAuth(fields.required(0), fields.required(1))
            "S_API" -> SessionCredential.ApiKey(fields.required(0))
            "S_OAUTH2" -> SessionCredential.OAuth2(
                accessToken = fields.required(0),
                refreshToken = fields.required(1),
                expiresAtEpochMs = fields.getOrNull(2)?.toLongOrNull(),
            )
            "S_COOKIE" -> SessionCredential.CookieSession(
                cookies = decodeMap(fields.required(0)),
                expiresAtEpochMs = fields.getOrNull(1)?.toLongOrNull(),
            )
            else -> null
        }
    }

    private fun record(tag: String, vararg fields: String?): String =
        (listOf(tag) + fields.map(::encodeNullable)).joinToString("|")

    private fun encodeNullable(value: String?): String =
        value?.let { encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8)) } ?: NULL

    private fun decodeNullable(value: String): String? =
        if (value == NULL) null else String(decoder.decode(value), StandardCharsets.UTF_8)

    private fun encodeMap(values: Map<String, String>): String = values.entries
        .sortedBy { it.key }
        .joinToString(",") { (key, value) -> "${encodeNullable(key)}.${encodeNullable(value)}" }

    private fun decodeMap(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        return raw.split(',').associate { entry ->
            val separator = entry.indexOf('.')
            require(separator >= 0) { "无效 Cookie 记录" }
            val key = requireNotNull(decodeNullable(entry.substring(0, separator)))
            val value = requireNotNull(decodeNullable(entry.substring(separator + 1)))
            key to value
        }
    }

    private inline fun <T> decode(
        raw: String,
        block: (tag: String, fields: List<String?>) -> T?,
    ): T? = try {
        val parts = raw.split('|')
        block(parts.first(), parts.drop(1).map(::decodeNullable))
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    private fun List<String?>.required(index: Int): String = requireNotNull(getOrNull(index))

    private const val NULL = "~"
}
