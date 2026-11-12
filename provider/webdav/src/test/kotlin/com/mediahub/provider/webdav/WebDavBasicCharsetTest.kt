package com.mediahub.provider.webdav

import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.Credentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * RFC 7617 / RFC 2617 字符集兼容策略：
 * 首个请求一律 UTF-8；只有 Basic challenge **未**声明 `charset="UTF-8"`
 * 且凭据可由 ISO-8859-1 无损表示时，才追加一次 ISO-8859-1 重试；
 * 不可无损表示或 challenge 已声明 UTF-8 时一律不重试。
 */
class WebDavBasicCharsetTest {
    private lateinit var web: MockWebServer
    @Before fun start() { web = MockWebServer().apply { start() } }
    @After fun stop() { web.shutdown() }

    @Test fun latin1RepresentableCredentialsRetryOnceWhenBasicChallengeOmitsUtf8() = runBlocking {
        val stack = WebDavTestStack(web.url("/dav/").toString(), LATIN1_USER)
        web.enqueue(basicChallenge())
        enqueueRoot()
        val result = stack.authProvider().authenticate(Credentials.WebDav(LATIN1_USER, WebDavTestStack.PASSWORD))
        assertTrue(result is AuthResult.Success)
        assertEquals(WebDavTestStack.PASSWORD, stack.storedPassword())
        assertEquals(2, web.requestCount)
        assertEquals(utf8Header(LATIN1_USER, WebDavTestStack.PASSWORD), web.takeRequest().getHeader("Authorization"))
        assertEquals(latin1Header(LATIN1_USER, WebDavTestStack.PASSWORD), web.takeRequest().getHeader("Authorization"))
    }

    @Test fun utf8ChallengeKeepsTheSingleLosslessRequest() = runBlocking {
        val stack = WebDavTestStack(web.url("/dav/").toString(), LATIN1_USER)
        web.enqueue(basicChallenge(charsetUtf8 = true))
        enqueueRoot()
        val result = stack.authProvider().authenticate(Credentials.WebDav(LATIN1_USER, "wrong " + WebDavTestStack.PASSWORD))
        assertTrue(result is AuthResult.Failure)
        assertEquals(1, web.requestCount)
        assertNull(stack.storedPassword())
        assertEquals(
            utf8Header(LATIN1_USER, "wrong " + WebDavTestStack.PASSWORD),
            web.takeRequest().getHeader("Authorization"),
        )
    }

    @Test fun nonLatin1CredentialsAreNotRetriedLossily() = runBlocking {
        val stack = WebDavTestStack(web.url("/dav/").toString(), "用户")
        web.enqueue(basicChallenge())
        enqueueRoot()
        val result = stack.authProvider().authenticate(Credentials.WebDav("用户", "测试口令"))
        assertTrue(result is AuthResult.Failure)
        assertEquals(1, web.requestCount)
        assertNull(stack.storedPassword())
    }

    @Test fun restoreSessionUsesTheSameRetryPolicy() = runBlocking {
        val stack = WebDavTestStack(web.url("/dav/").toString(), LATIN1_USER)
        stack.storePassword()
        web.enqueue(basicChallenge())
        enqueueRoot()
        val state = stack.authProvider().restoreSession()
        assertTrue(state is AuthSessionState.Authenticated)
        assertEquals(2, web.requestCount)
        val first = web.takeRequest()
        val second = web.takeRequest()
        assertEquals(utf8Header(LATIN1_USER, WebDavTestStack.PASSWORD), first.getHeader("Authorization"))
        assertEquals(latin1Header(LATIN1_USER, WebDavTestStack.PASSWORD), second.getHeader("Authorization"))
    }

    private fun basicChallenge(charsetUtf8: Boolean = false): MockResponse {
        val challenge = if (charsetUtf8) {
            "Basic realm=\"fixture\", charset=\"UTF-8\""
        } else {
            "Basic realm=\"fixture\""
        }
        return MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", challenge)
    }

    private fun enqueueRoot() {
        web.enqueue(
            MockResponse()
                .setResponseCode(207)
                .setHeader("Content-Type", "application/xml")
                .setBody(WebDavFixtures.multistatus(WebDavFixtures.collection("/dav/", "Root")))
        )
    }

    private fun utf8Header(username: String, password: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))

    private fun latin1Header(username: String, password: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.ISO_8859_1))

    private companion object {
        /** Latin-1 可无损表示的非 ASCII 用户名。 */
        const val LATIN1_USER = "café"
    }
}
