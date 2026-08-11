package com.mediahub.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 Android Keystore（AES/GCM）的加密存储。
 *
 * - 密钥保存在系统 Keystore（硬件级保护），密文写入应用私有 SharedPreferences。
 * - GCM 自带完整性校验；IV 每次加密随机生成并与密文一同存储。
 * - 若密钥被系统作废（如锁屏凭据变更），自动重建密钥并清空旧密文（旧会话需重新登录）。
 */
class KeystoreSecretStorage(
    context: Context,
    private val logger: Logger,
) : SecretStorage {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val record = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + SEPARATOR +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            prefs.edit().putString(key, record).apply()
        } catch (e: Exception) {
            logger.e(LogTag.SECURITY, "SecretStorage.put 失败 key=$key", e)
            throw e
        }
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        val record = prefs.getString(key, null) ?: return@withContext null
        try {
            val (ivB64, dataB64) = record.split(SEPARATOR, limit = 2)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val data = Base64.decode(dataB64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: KeyPermanentlyInvalidatedException) {
            logger.w(LogTag.SECURITY, "密钥已失效，清空加密存储 key=$key", e)
            clearAll()
            null
        } catch (e: Exception) {
            logger.e(LogTag.SECURITY, "SecretStorage.get 失败 key=$key", e)
            null
        }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key).apply()
    }

    override suspend fun contains(key: String): Boolean = prefs.contains(key)

    private fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "mediahub_secret_store"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mediahub_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = ":"
    }
}
