package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5 (Agent C) - red reproduction for F-C1-2: EndpointTestService surfaces the raw exception text.
 *
 * The production code builds the user-visible error as "API test failed: " + e.message. Provider and
 * network exceptions routinely echo the request URL, and a user-entered server address may legitimately
 * carry user-info or a sensitive query parameter. Every other boundary in this project maps failures to
 * a fixed message and/or routes details through Redactor; EndpointTestService does not.
 *
 * The leak is forced deterministically by handing the service a probe URL that OkHttp rejects while
 * parsing, so the IllegalArgumentException message contains the full URL verbatim. No real host is
 * contacted. The sensitive markers below are synthetic.
 *
 * This test asserts the CONTRACT (no URL/credential fragment in the user-visible error), not a specific
 * wording, so any fixed-message or typed-result fix satisfies it.
 */
class EndpointTestServiceRedactionTest {

    private val host = "secret-host.example"
    private val token = "SYNTHETIC-TOKEN-VALUE"
    private val userInfo = "agent:SYNTHETIC-PASSWORD"

    private fun service() = EndpointTestService(HttpClientFactory(StdoutLogger()), clock = { 0L })

    @Test
    fun `user visible error must not echo host user-info or query credentials`() = runBlocking {
        // A space in the URL makes OkHttp reject the request while parsing it, and the resulting
        // exception message contains the whole URL including user-info and the query.
        val base = "http://" + userInfo + "@" + host + ":8920/emby?api_key=" + token
        val result = service().test(base, "/System/Info/Public bad path")
        val error = result.error.orEmpty()
        assertTrue("必须报告为失败", error.isNotEmpty())
        assertFalse("用户可见错误不得回显主机名；实际=" + error, error.contains(host, ignoreCase = true))
        assertFalse("用户可见错误不得回显 query 凭据；实际=" + error, error.contains(token, ignoreCase = true))
        assertFalse("用户可见错误不得回显 user-info；实际=" + error, error.contains("SYNTHETIC-PASSWORD", ignoreCase = true))
    }
}
