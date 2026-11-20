package com.mediahub.provider.base

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ProviderException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * apiCall 的取消语义：协程取消必须原样透传，不得被收敛成 ProviderException。
 *
 * 背景：apiCall 末尾的 catch (e: Exception) 会把 CancellationException（它是
 * Exception 子类）包装成 ProviderException.Unknown。调用方（Compose 页面 /
 * 页面级协程）收到"未知错误"后会当作真实失败弹错，而结构化的取消信号丢失，
 * 上层无法区分"用户离开页面"与"服务器出错"。
 */
class BaseMediaServerProviderCancellationTest {

    private class RecordingLogger : Logger {
        val errors = mutableListOf<String>()
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) {
            errors += message
        }
    }

    private class InMemorySecretStorage : SecretStorage {
        private val values = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String) {
            values[key] = value
        }

        override suspend fun get(key: String): String? = values[key]
        override suspend fun remove(key: String) {
            values.remove(key)
        }

        override suspend fun contains(key: String): Boolean = values.containsKey(key)
    }

    private class TestProvider(
        logger: Logger,
        tokenStore: TokenStore = TokenStore(InMemorySecretStorage()),
    ) : BaseMediaServerProvider(
        server = MediaServer(
            id = "s1",
            name = "测试",
            type = ServerType.EMBY,
            baseUrl = "http://127.0.0.1:1",
            createdAtEpochMs = 0,
        ),
        apiClient = ApiClient(OkHttpClient(), logger = logger),
        mediaHttpClient = MediaHttpClient(OkHttpClient(), logger),
        tokenStore = tokenStore,
        logger = logger,
    ) {
        override suspend fun authHeaders(): Map<String, String> = emptyMap()

        suspend fun <T> run(block: suspend () -> T): T = apiCall(block)
    }

    @Test
    fun `cancellation inside the block is rethrown instead of becoming unknown error`() = runBlocking {
        val logger = RecordingLogger()
        val provider = TestProvider(logger)
        val started = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.async {
            provider.run {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertTrue("取消必须以 CancellationException 结束，实际=$thrown", thrown is CancellationException)
        assertNull("取消不得被包装成 ProviderException", thrown as? ProviderException)
        assertEquals("取消不得写错误日志", emptyList<String>(), logger.errors)
        scope.cancel()
    }

    @Test
    fun `timeout cancellation is rethrown instead of becoming unknown error`() = runBlocking {
        val logger = RecordingLogger()
        val provider = TestProvider(logger)
        val thrown = runCatching {
            withTimeoutOrNull(20) {
                provider.run { awaitCancellation() }
            }
        }.exceptionOrNull()
        assertNull("超时取消不得被包装成 ProviderException", thrown as? ProviderException)
        assertEquals("取消路径不得写错误日志", emptyList<String>(), logger.errors)
    }

    @Test
    fun `genuine failures are still wrapped as before`() = runBlocking {
        val logger = RecordingLogger()
        val provider = TestProvider(logger)
        val thrown = runCatching {
            provider.run<Unit> { throw IllegalStateException("boom") }
        }.exceptionOrNull()
        assertTrue("非取消异常仍应收敛为 ProviderException.Unknown", thrown is ProviderException.Unknown)
        assertEquals(1, logger.errors.size)
    }

    @Test
    fun `io failure still maps to network error`() = runBlocking {
        val logger = RecordingLogger()
        val provider = TestProvider(logger)
        val thrown = runCatching {
            provider.run<Unit> { throw IOException("connect reset") }
        }.exceptionOrNull()
        assertTrue("IOException 仍映射为 Network", thrown is ProviderException.Network)
        assertEquals(emptyList<String>(), logger.errors)
    }

    @Test
    fun `provider exception from the block passes through unchanged`() = runBlocking {
        val logger = RecordingLogger()
        val provider = TestProvider(logger)
        val original = ProviderException.AuthExpired("s1")
        val thrown = runCatching {
            provider.run<Unit> { throw original }
        }.exceptionOrNull()
        assertSame("ProviderException 必须原实例透传，不得二次包装", original, thrown)
        assertEquals(emptyList<String>(), logger.errors)
    }

    @Test
    fun `cancellation carrying a cause is rethrown as the same instance`() = runBlocking {
        val logger = RecordingLogger()
        val provider = TestProvider(logger)
        val original = CancellationException("user left the screen")
        val thrown = runCatching {
            provider.run<Unit> { throw original }
        }.exceptionOrNull()
        assertSame("取消异常必须原实例透传", original, thrown)
        assertEquals(emptyList<String>(), logger.errors)
    }
}
