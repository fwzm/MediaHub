package com.mediahub.core.security

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CredentialVault.clear 的失败韧性契约（ADR-016 级联清理链）：
 * 单个凭据种类的删除失败不得让其余种类逃过清理，且必须把失败（含具体种类）暴露给调用方，
 * 使 RemoveServerUseCase 的级联清理在"确实没清干净"时中止而不是假装成功。
 *
 * 同时钉住 W6-100 的日志契约：抛出的异常类型与消息必须仍是**原始失败**，
 * 种类信息只能以附加诊断（suppressed）形式出现，不得替换顶层异常。
 */
class CredentialVaultClearResilienceTest {

    /** 可按 key 定向失败的 SecretStorage；记录每次 remove 的 key 顺序。 */
    private class FlakySecretStorage(
        private val failures: Map<String, Throwable> = emptyMap(),
    ) : SecretStorage {
        val map = linkedMapOf<String, String>()
        val removeAttempts = mutableListOf<String>()

        override suspend fun put(key: String, value: String) {
            map[key] = value
        }

        override suspend fun get(key: String): String? = map[key]

        override suspend fun remove(key: String) {
            removeAttempts += key
            failures[key]?.let { throw it }
            map.remove(key)
        }

        override suspend fun contains(key: String): Boolean = map.containsKey(key)
    }

    private fun keyOf(serverId: String, kind: CredentialVault.CredentialKind) =
        "cred:" + serverId + ":" + kind.name

    private suspend fun seed(storage: SecretStorage, serverId: String) {
        CredentialVault.CredentialKind.entries.forEach { kind ->
            storage.put(keyOf(serverId, kind), "value-" + kind.name)
        }
    }

    private fun kindsNamedBy(failure: Throwable): List<CredentialVault.CredentialKind> =
        failure.suppressed.filterIsInstance<CredentialVault.CredentialCleanupFailure>()
            .map { it.kind }

    @Test
    fun `clear keeps going when one credential removal fails`() = runBlocking {
        val passwordFailure = IOException("keyring unavailable")
        val storage = FlakySecretStorage(
            failures = mapOf(keyOf("srv1", CredentialVault.CredentialKind.PASSWORD) to passwordFailure),
        )
        seed(storage, "srv1")
        val vault = CredentialVault(storage)

        try {
            vault.clear("srv1")
            fail("clear 必须把失败暴露给调用方")
        } catch (expected: IOException) {
            // 预期：失败被上报，而不是静默成功
        }

        CredentialVault.CredentialKind.entries.forEach { kind ->
            val remaining = storage.map[keyOf("srv1", kind)]
            if (kind == CredentialVault.CredentialKind.PASSWORD) {
                assertTrue("失败的种类本身应保持原状以便重试：" + kind, remaining != null)
            } else {
                assertNull("其余种类必须仍被清理：" + kind, remaining)
            }
        }
        assertEquals(
            "每个种类都必须被尝试一次",
            CredentialVault.CredentialKind.entries.size,
            storage.removeAttempts.size,
        )
    }

    @Test
    fun `clear reports the failing kind without replacing the original failure`() = runBlocking {
        val passwordFailure = IOException("keyring unavailable")
        val storage = FlakySecretStorage(
            failures = mapOf(keyOf("srv2", CredentialVault.CredentialKind.PASSWORD) to passwordFailure),
        )
        seed(storage, "srv2")
        val vault = CredentialVault(storage)

        val thrown = try {
            vault.clear("srv2")
            null
        } catch (error: Throwable) {
            error
        }

        assertSame("顶层异常必须仍是原始失败（W6-100 日志行依赖它）", passwordFailure, thrown)
        assertEquals(
            "种类诊断必须点名失败的 serverId 与 kind",
            listOf(CredentialVault.CredentialKind.PASSWORD),
            kindsNamedBy(thrown!!),
        )
        val detail = thrown.suppressed.filterIsInstance<CredentialVault.CredentialCleanupFailure>().single()
        assertEquals("srv2", detail.serverId)
        assertTrue("诊断消息必须点名种类，实际：" + detail.message, detail.message.orEmpty().contains("PASSWORD"))
    }

    @Test
    fun `clear reports the first failure when several kinds fail`() = runBlocking {
        val passwordFailure = IOException("keyring unavailable")
        val cookieFailure = IOException("cookie store locked")
        val storage = FlakySecretStorage(
            failures = mapOf(
                keyOf("srv3", CredentialVault.CredentialKind.PASSWORD) to passwordFailure,
                keyOf("srv3", CredentialVault.CredentialKind.COOKIE) to cookieFailure,
            ),
        )
        seed(storage, "srv3")
        val vault = CredentialVault(storage)

        val thrown = try {
            vault.clear("srv3")
            null
        } catch (error: Throwable) {
            error
        }

        assertSame("必须抛首个失败", passwordFailure, thrown)
        assertEquals(
            "诊断只记录首个失败的种类",
            listOf(CredentialVault.CredentialKind.PASSWORD),
            kindsNamedBy(thrown!!),
        )
        assertEquals(
            "两个失败种类都应被尝试",
            CredentialVault.CredentialKind.entries.size,
            storage.removeAttempts.size,
        )
    }

    @Test
    fun `clear removes every kind when the storage is healthy`() = runBlocking {
        val storage = FlakySecretStorage()
        seed(storage, "srv4")
        val vault = CredentialVault(storage)

        vault.clear("srv4")

        assertTrue("健康存储下不得残留任何凭据", storage.map.isEmpty())
        assertEquals(CredentialVault.CredentialKind.entries.size, storage.removeAttempts.size)
    }

    @Test
    fun `clear propagates cancellation instead of converting it into a cleanup failure`() = runBlocking {
        val storage = FlakySecretStorage(
            failures = mapOf(
                keyOf("srv5", CredentialVault.CredentialKind.PASSWORD) to CancellationException("调用方取消"),
            ),
        )
        seed(storage, "srv5")
        val vault = CredentialVault(storage)

        try {
            vault.clear("srv5")
            fail("取消必须原样透传")
        } catch (cancellation: CancellationException) {
            assertEquals("调用方取消", cancellation.message)
            assertTrue("取消不得被包装成清理失败诊断", cancellation.suppressed.isEmpty())
        }
    }

    @Test
    fun `clear stops at cancellation without touching later kinds`() = runBlocking {
        val storage = FlakySecretStorage(
            failures = mapOf(
                keyOf("srv6", CredentialVault.CredentialKind.PASSWORD) to CancellationException("调用方取消"),
            ),
        )
        seed(storage, "srv6")
        val vault = CredentialVault(storage)

        runCatching { vault.clear("srv6") }

        assertEquals("取消后不得继续删除其余种类", 1, storage.removeAttempts.size)
        assertEquals(
            keyOf("srv6", CredentialVault.CredentialKind.PASSWORD),
            storage.removeAttempts.first(),
        )
    }
}
