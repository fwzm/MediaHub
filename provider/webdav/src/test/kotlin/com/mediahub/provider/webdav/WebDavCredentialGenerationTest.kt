package com.mediahub.provider.webdav

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * 凭据世代守卫（Z3 安全门禁）：**迟到的 401 不得清掉较新身份的密码**。
 *
 * 场景：在途 restoreSession 持旧密码探测期间，用户用新密码重新认证
 * （savePassword 增代）；旧请求迟到的 401 触发清理时，世代不符必须跳过。
 */
class WebDavCredentialGenerationTest {

    private lateinit var webServer: MockWebServer
    private lateinit var stack: WebDavTestStack

    @Before
    fun setUp() {
        webServer = MockWebServer().apply { start() }
        stack = WebDavTestStack(webServer.url("/dav/").toString())
    }

    @After
    fun tearDown() {
        webServer.shutdown()
    }

    @Test
    fun `stale 401 after re-authentication does not clear the newer password`() = runBlocking {
        stack.storePassword("old-password")
        val staleCredential = stack.credentialStore.readPassword(stack.server.id)!!
        assertEquals("old-password", staleCredential.password)

        // 重新认证：世代递增，凭据换新
        stack.storePassword("new-password")
        val fresh = stack.credentialStore.readPassword(stack.server.id)!!
        assertFalse("新身份世代必须不同", fresh.generation == staleCredential.generation)

        // 迟到失败按旧世代清理：必须被世代守卫拒绝
        stack.credentialStore.clearIfStill(stack.server.id, staleCredential)
        assertEquals("较新身份的密码不得被迟到失败清除", "new-password", stack.credentialStore.readPasswordValue(stack.server))
    }

    @Test
    fun `401 with unchanged identity still clears the password`() = runBlocking {
        stack.storePassword("only-password")
        val credential = stack.credentialStore.readPassword(stack.server.id)!!

        stack.credentialStore.clearIfStill(stack.server.id, credential)
        assertEquals("同代 401 清理语义保持不变", null, stack.credentialStore.readPasswordValue(stack.server))
    }

    @Test
    fun `provider level stale 401 keeps new password usable`() = runBlocking {
        stack.storePassword("stale-password")
        // 确定性交错：restoreSession 先读走旧凭据，再被服务端 barrier 挡住；
        // 期间用户重新认证（世代递增），放行后服务端对旧凭据返回 401。
        val requestEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        webServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                requestEntered.complete(Unit)
                runBlocking { release.await() }
                return MockResponse().setResponseCode(401)
            }
        }

        val restore = async {
            stack.authProvider().restoreSession()
        }
        requestEntered.await()
        stack.storePassword("fresh-password") // 迟到窗口内重新认证：gen 1 → 2
        release.complete(Unit)

        val state = restore.await()
        assertEquals(false, state is com.mediahub.provider.api.AuthSessionState.Authenticated)
        assertEquals("较新身份的密码不得被迟到 401 清除", "fresh-password", stack.credentialStore.readPasswordValue(stack.server))
    }
}
