package com.mediahub.provider.api

/**
 * 一次认证请求使用的短生命周期凭据。所有实现都覆盖 [toString]，避免调试输出泄露密钥。
 */
sealed interface Credentials {
    data object Anonymous : Credentials

    data class UsernamePassword(val username: String, val password: String) : Credentials {
        override fun toString(): String = "UsernamePassword(REDACTED)"
    }

    data class BasicAuth(val username: String, val password: String) : Credentials {
        override fun toString(): String = "BasicAuth(REDACTED)"
    }

    data class BearerToken(val token: String) : Credentials {
        override fun toString(): String = "BearerToken(REDACTED)"
    }

    data class ApiKey(val apiKey: String) : Credentials {
        override fun toString(): String = "ApiKey(REDACTED)"
    }

    data class OAuth2(
        val authorizationCode: String,
        val codeVerifier: String? = null,
        val redirectUri: String? = null,
    ) : Credentials {
        override fun toString(): String = "OAuth2(REDACTED)"
    }

    data class DeviceCode(val deviceCode: String) : Credentials {
        override fun toString(): String = "DeviceCode(REDACTED)"
    }

    data class RefreshToken(val refreshToken: String) : Credentials {
        override fun toString(): String = "RefreshToken(REDACTED)"
    }

    data class CookieSession(val cookies: Map<String, String>) : Credentials {
        override fun toString(): String = "CookieSession(REDACTED)"
    }

    data class QrLogin(val payload: String) : Credentials {
        override fun toString(): String = "QrLogin(REDACTED)"
    }
}

/** Provider 登录成功后可加密持久化的长期会话凭据。 */
sealed interface SessionCredential {
    data class AccessToken(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiresAtEpochMs: Long? = null,
    ) : SessionCredential {
        override fun toString(): String = "AccessToken(REDACTED)"
    }

    data class BasicAuth(val username: String, val password: String) : SessionCredential {
        override fun toString(): String = "BasicAuth(REDACTED)"
    }

    data class ApiKey(val apiKey: String) : SessionCredential {
        override fun toString(): String = "ApiKey(REDACTED)"
    }

    data class OAuth2(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochMs: Long? = null,
    ) : SessionCredential {
        override fun toString(): String = "OAuth2(REDACTED)"
    }

    data class CookieSession(
        val cookies: Map<String, String>,
        val expiresAtEpochMs: Long? = null,
    ) : SessionCredential {
        override fun toString(): String = "CookieSession(REDACTED)"
    }
}

/** 唯一允许持久化认证凭据的抽象；实现必须使用加密存储。 */
interface CredentialVault {
    suspend fun savePending(serverId: String, credentials: Credentials)
    suspend fun readPending(serverId: String): Credentials?
    suspend fun saveSession(serverId: String, session: SessionCredential)
    suspend fun readSession(serverId: String): SessionCredential?
    suspend fun clearPending(serverId: String)
    suspend fun clear(serverId: String)
}

sealed interface AuthenticationDisposition {
    data class Authenticated(val result: AuthResult.Success) : AuthenticationDisposition
    data object DeferredUntilProviderImplementation : AuthenticationDisposition
}

/** 统一控制“短期输入凭据 → Provider 认证 → 加密会话”的生命周期。 */
interface AuthenticationCoordinator {
    suspend fun authenticateOrDefer(
        handle: ProviderHandle,
        credentials: Credentials,
    ): AuthenticationDisposition

    suspend fun clear(serverId: String)
}
