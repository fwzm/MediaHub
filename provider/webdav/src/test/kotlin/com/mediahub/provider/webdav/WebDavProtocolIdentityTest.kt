package com.mediahub.provider.webdav

import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ProviderException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WebDavProtocolIdentityTest {
    private lateinit var web: MockWebServer

    @Before fun start() { web = MockWebServer().apply { start() } }
    @After fun stop() { web.shutdown() }

    @Test fun aPlainHttpSuccessDoesNotProveDavIdentity() = runBlocking {
        rejected(probe())
    }

    @Test fun emptyAndWhitespaceDeclarationsAreRejected() = runBlocking {
        for (value in listOf("", " ", "\t")) rejected(probe(listOf(value)))
    }

    @Test fun classTwoOrThreeWithoutClassOneIsRejected() = runBlocking {
        for (value in listOf("2", "3", "2, 3")) rejected(probe(listOf(value)))
    }

    @Test fun anOpaqueValueOrNearMatchDoesNotProveClassOne() = runBlocking {
        for (value in listOf("enabled", "class1", "01", "1.0", "11")) rejected(probe(listOf(value)))
    }

    @Test fun aClassNumberInsideAnExtensionUriIsNotAClassToken() = runBlocking {
        for (value in listOf("<urn:fixture:1>", "<urn:fixture:x,1,y>", "<https://example.invalid/x,1,y>")) {
            rejected(probe(listOf(value)))
        }
    }

    @Test fun quotedClassOneIsNotAComplianceToken() = runBlocking {
        rejected(probe(listOf("\"1\"")))
    }

    @Test fun malformedListsAreNotRescuedByAnEarlierClassOne() = runBlocking {
        for (value in listOf("1; other", "1, <urn:unclosed", "1, \"two\"", "1, <relative>", "1, <urn:x#fragment>")) {
            rejected(probe(listOf(value)))
        }
    }

    @Test fun splittingAnExtensionAcrossHeadersCannotInventClassOne() = runBlocking {
        rejected(probe(listOf("<urn:fixture:x", "1,y>")))
    }

    @Test fun validClassOneListsAndExtensionsAreAccepted() = runBlocking {
        for (value in listOf("1", "1, 2, 3", "3,\t1", ", 1,", "<urn:fixture:x,1,y>, 1", "1, extension-token")) {
            val status = probe(listOf(value))
            assertTrue(value, status.ok)
            assertTrue(status.message.orEmpty().contains("DAV"))
            assertNull(status.errorCode)
        }
    }

    @Test fun repeatedHeaderFieldsAreCombinedInEitherOrder() = runBlocking {
        for (values in listOf(listOf("1", "extension-token"), listOf("extension-token", "1"))) {
            assertTrue(probe(values).ok)
        }
    }

    @Test fun peerCapabilityPayloadIsNotReflectedIntoTheStatus() = runBlocking {
        val marker = "fixture-private-marker"
        val status = probe(listOf("1, <urn:fixture:$marker>"))
        assertTrue(status.ok)
        assertFalse(status.message.orEmpty().contains(marker))
    }

    @Test fun authenticationAndHttpFailuresRetainTheirExistingMeaning() = runBlocking {
        for ((code, expected) in listOf(401 to ProviderException.ErrorCode.AUTH_REQUIRED,
            403 to ProviderException.ErrorCode.HTTP, 404 to ProviderException.ErrorCode.HTTP,
            500 to ProviderException.ErrorCode.HTTP)) {
            val status = probe(listOf("1"), code)
            assertFalse(status.ok)
            assertEquals(expected, status.errorCode)
        }
    }

    @Test fun aRedirectIsNotFollowedEvenWhenItsTargetAdvertisesDav() = runBlocking {
        MockWebServer().use { target ->
            target.start()
            target.enqueue(MockResponse().setHeader("DAV", "1"))
            web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/dav/")))
            val status = provider().testConnection()
            assertFalse(status.ok)
            assertEquals(ProviderException.ErrorCode.HTTP, status.errorCode)
            assertEquals(1, web.requestCount)
            assertEquals(0, target.requestCount)
        }
    }

    @Test fun cancellingAnInFlightProbeCancelsTheRealCall() = runBlocking {
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = async(Dispatchers.IO) { provider().testConnection() }
        try {
            val request = withContext(Dispatchers.IO) { web.takeRequest(10, TimeUnit.SECONDS) }
            assertNotNull("probe never reached the owned server", request)
            withTimeout(10_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { job.cancelAndJoin() }
    }

    @Test fun protocolDiscoveryDoesNotAccessSecretStorage() = runBlocking {
        web.enqueue(MockResponse().setHeader("DAV", "1"))
        assertTrue(provider(object : SecretStorage {
            override suspend fun put(key: String, value: String): Unit = error("probe must not write secrets")
            override suspend fun get(key: String): String? = error("probe must not read secrets")
            override suspend fun remove(key: String): Unit = error("probe must not delete secrets")
            override suspend fun contains(key: String): Boolean = error("probe must not inspect secrets")
        }).testConnection().ok)
        assertAnonymousOptions()
    }

    private fun provider(storage: SecretStorage = FakeSecretStorage()) = WebDavProviderFactory(
        HttpClientFactory(StdoutLogger()), TokenStore(storage), CredentialVault(storage),
        WebDavCredentialCoordinator(), StdoutLogger(),
    ).create(MediaServer("protocol-fixture", "Protocol fixture", ServerType.WEBDAV,
        web.url("/dav/").toString(), createdAtEpochMs = 0)).provider

    private suspend fun probe(values: List<String> = emptyList(), code: Int = 200): ConnectionStatus {
        val response = MockResponse().setResponseCode(code)
        values.forEach { response.addHeader("DAV", it) }
        web.enqueue(response)
        val before = web.requestCount
        val result = provider().testConnection()
        assertEquals(before + 1, web.requestCount)
        assertAnonymousOptions()
        return result
    }

    private fun assertAnonymousOptions() {
        val request = web.takeRequest()
        assertEquals("OPTIONS", request.method)
        assertEquals("/dav/", request.path)
        assertEquals(0L, request.bodySize)
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("Cookie"))
    }

    private fun rejected(status: ConnectionStatus) {
        assertFalse("non-DAV response must not be accepted", status.ok)
        assertEquals(ProviderException.ErrorCode.CONNECTION, status.errorCode)
    }
}
