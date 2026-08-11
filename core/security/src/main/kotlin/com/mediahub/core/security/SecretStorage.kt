package com.mediahub.core.security

/**
 * 敏感信息存储抽象（Token / Cookie / 密码等）。
 * 唯一允许的实现基于 Android Keystore 加密（见 [KeystoreSecretStorage]）。
 * 任何业务代码不得绕过该接口直接明文落盘敏感信息。
 */
interface SecretStorage {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun remove(key: String)
    suspend fun contains(key: String): Boolean
}
