package com.mediahub.provider.webdav

import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.AuthSessionErrorKind
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.Credentials
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WebDavAuthenticationEvidenceTest {
    private lateinit var web: MockWebServer
    private lateinit var stack: WebDavTestStack
    @Before fun start() { web = MockWebServer().apply { start() }; stack = WebDavTestStack(web.url("/dav/").toString()) }
    @After fun stop() { web.shutdown() }

    @Test fun wellFormedHtmlCannotAuthenticateOrPersistAPassword() = runBlocking {
        rejectLogin("<html><body>Login portal</body></html>")
    }

    @Test fun aWrongNamespaceCannotAuthenticate() = runBlocking {
        rejectLogin("<x:multistatus xmlns:x=\"urn:not-dav\"/>")
    }

    @Test fun anEmptyDepthZeroMultistatusDoesNotProveAccessToTheRoot() = runBlocking {
        rejectLogin(WebDavFixtures.multistatus())
    }

    @Test fun unrelatedResourceMetadataCannotAuthenticateTheConfiguredRoot() = runBlocking {
        rejectLogin(WebDavFixtures.multistatus(WebDavFixtures.collection("/other/", "Other")))
    }

    @Test fun aForeignOriginHrefCannotAuthenticateTheConfiguredRoot() = runBlocking {
        rejectLogin(WebDavFixtures.multistatus(WebDavFixtures.collection("https://example.invalid/dav/", "Other origin")))
    }

    @Test fun aDifferentPathCaseIsNotTheSameRoot() = runBlocking {
        rejectLogin(WebDavFixtures.multistatus(WebDavFixtures.collection("/DAV/", "Different path")))
    }

    @Test fun davPropertiesHiddenInsideHtmlDoNotAuthenticate() = runBlocking {
        val response = WebDavFixtures.collection("/dav/", "Injected")
        rejectLogin("<html xmlns:D=\"DAV:\">$response</html>")
    }

    @Test fun anInvalidRestoreResponseKeepsExistingCredentialsAndDoesNotPublishAuthenticated() = runBlocking {
        stack.storePassword()
        enqueue("<html><body>Login portal</body></html>")
        val result = stack.authProvider().restoreSession()
        assertTrue(result is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.INVALID_RESPONSE, (result as AuthSessionState.Error).kind)
        assertEquals(WebDavTestStack.PASSWORD, stack.storedPassword())
        assertRootProbe()
    }

    @Test fun aValidRootResponseAuthenticatesAndPersistsOnlyAfterValidation() = runBlocking {
        enqueue(WebDavFixtures.multistatus(WebDavFixtures.collection("/dav/", "Root")))
        assertTrue(stack.authProvider().authenticate(Credentials.WebDav("alice", WebDavTestStack.PASSWORD)) is AuthResult.Success)
        assertEquals(WebDavTestStack.PASSWORD, stack.storedPassword())
        assertRootProbe()
    }

    @Test fun chineseCredentialsUseUtf8OnTheFirstProtectedRequest() = runBlocking {
        val username = "用户"
        val password = "测试口令"
        stack = WebDavTestStack(web.url("/dav/").toString(), username)
        enqueue(WebDavFixtures.multistatus(WebDavFixtures.collection("/dav/", "Root")))
        assertTrue(stack.authProvider().authenticate(Credentials.WebDav(username, password)) is AuthResult.Success)
        val request = web.takeRequest()
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
        assertEquals(expected, request.getHeader("Authorization"))
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.getHeader("Depth"))
        assertEquals(1, web.requestCount)
        assertEquals(password, stack.storedPassword())
    }

    @Test fun aUtf8ChallengeDoesNotCauseALossyOrIdenticalCredentialRetry() = runBlocking {
        stack = WebDavTestStack(web.url("/dav/").toString(), "用户")
        web.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"fixture\", charset=\"UTF-8\""))
        enqueue(WebDavFixtures.multistatus(WebDavFixtures.collection("/dav/", "Root")))
        val result = stack.authProvider().authenticate(Credentials.WebDav("用户", "错误的测试口令"))
        assertTrue(result is AuthResult.Failure)
        assertEquals(1, web.requestCount)
        assertNull(stack.storedPassword())
        val request = web.takeRequest()
        val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("用户:错误的测试口令".toByteArray(Charsets.UTF_8))
        assertEquals(expected, request.getHeader("Authorization"))
    }

    @Test fun aMatchingHrefWithOnlyDeniedPropertiesDoesNotAuthenticate() = runBlocking {
        rejectLogin("""<D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/</D:href>
            <D:propstat><D:prop/><D:status>HTTP/1.1 403 Forbidden</D:status></D:propstat>
            </D:response></D:multistatus>""")
    }

    @Test fun aResponseHiddenUnderAnExtensionIsNotARootResponse() = runBlocking {
        val response = WebDavFixtures.collection("/dav/", "Injected")
        rejectLogin(WebDavFixtures.multistatus("<x:wrapper xmlns:x=\"urn:fixture\">$response</x:wrapper>"))
    }

    @Test fun anExtensionHrefCannotReplaceTheActualResponseHref() = runBlocking {
        rejectLogin("""<D:multistatus xmlns:D="DAV:" xmlns:x="urn:fixture"><D:response>
            <D:href>/other/</D:href><D:propstat><D:prop>
            <x:custom><D:href>/dav/</D:href></x:custom><D:resourcetype><D:collection/></D:resourcetype>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>""")
    }

    @Test fun markupInsideTheHrefDoesNotProveARootIdentity() = runBlocking {
        rejectLogin("""<D:multistatus xmlns:D="DAV:" xmlns:x="urn:fixture"><D:response>
            <D:href><x:span>/dav/</x:span></D:href><D:status>HTTP/1.1 200 OK</D:status>
            </D:response></D:multistatus>""")
    }

    @Test fun absoluteRelativeAndUnreservedEncodedRootHrefsRemainCompatible() = runBlocking {
        for (href in listOf(web.url("/dav").toString(), "./", "/%64av/")) {
            enqueue(WebDavFixtures.multistatus(WebDavFixtures.collection(href, "Root")))
            assertTrue(href, stack.authProvider().authenticate(Credentials.WebDav("alice", WebDavTestStack.PASSWORD)) is AuthResult.Success)
            val request = web.takeRequest()
            assertEquals("PROPFIND", request.method)
        }
    }

    @Test fun anExplicitSuccessfulResponseStatusCanRepresentTheRoot() = runBlocking {
        enqueue("""<D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/</D:href>
            <D:status>HTTP/1.1 200 OK</D:status></D:response></D:multistatus>""")
        assertTrue(stack.authProvider().authenticate(Credentials.WebDav("alice", WebDavTestStack.PASSWORD)) is AuthResult.Success)
        assertRootProbe()
    }

    @Test fun anUnrelatedRestoreResponseKeepsCredentialsWithoutAuthenticating() = runBlocking {
        stack.storePassword()
        enqueue(WebDavFixtures.multistatus(WebDavFixtures.collection("/other/", "Other")))
        val state = stack.authProvider().restoreSession()
        assertTrue(state is AuthSessionState.Error)
        assertEquals(AuthSessionErrorKind.INVALID_RESPONSE, (state as AuthSessionState.Error).kind)
        assertEquals(WebDavTestStack.PASSWORD, stack.storedPassword())
        assertRootProbe()
    }

    private suspend fun rejectLogin(xml: String) {
        enqueue(xml)
        val result = stack.authProvider().authenticate(Credentials.WebDav("alice", WebDavTestStack.PASSWORD))
        assertTrue("non-root/non-DAV response must not authenticate", result is AuthResult.Failure)
        assertNull("invalid response must not persist a password", stack.storedPassword())
        assertRootProbe()
    }

    private fun enqueue(xml: String) { web.enqueue(MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(xml)) }
    private fun assertRootProbe() {
        assertEquals(1, web.requestCount)
        val request = web.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("0", request.getHeader("Depth"))
        assertEquals("/dav/", request.path)
    }
}
