package com.mediahub.feature.player

import com.mediahub.provider.api.ProviderException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResolveRetryPolicyTest {

    @Test
    fun `network error is retried only once`() {
        val error = ProviderException.Network("srv-1", IOException("boom"))
        assertTrue(PlaybackResolveRetryPolicy.shouldRetry(error, attempt = 0))
        assertFalse(PlaybackResolveRetryPolicy.shouldRetry(error, attempt = 1))
    }

    @Test
    fun `connection error is retryable`() {
        assertTrue(
            PlaybackResolveRetryPolicy.shouldRetry(
                ProviderException.Connection("srv-1", "连接失败", IOException("refused")),
                attempt = 0,
            ),
        )
    }

    @Test
    fun `server errors are retryable but client errors are not`() {
        assertTrue(PlaybackResolveRetryPolicy.shouldRetry(ProviderException.Http("srv-1", 500, "/a"), 0))
        assertTrue(PlaybackResolveRetryPolicy.shouldRetry(ProviderException.Http("srv-1", 503, "/a"), 0))
        assertFalse(PlaybackResolveRetryPolicy.shouldRetry(ProviderException.Http("srv-1", 404, "/a"), 0))
        assertFalse(PlaybackResolveRetryPolicy.shouldRetry(ProviderException.Http("srv-1", 400, "/a"), 0))
    }

    @Test
    fun `plain io exception is retryable`() {
        assertTrue(PlaybackResolveRetryPolicy.shouldRetry(IOException("timeout"), attempt = 0))
    }

    @Test
    fun `non transient provider errors never retry`() {
        assertFalse(PlaybackResolveRetryPolicy.shouldRetry(ProviderException.AuthRequired("srv-1"), 0))
        assertFalse(PlaybackResolveRetryPolicy.shouldRetry(ProviderException.NotYetImplemented("srv-1", "播放"), 0))
        assertFalse(PlaybackResolveRetryPolicy.shouldRetry(ProviderException.RateLimited("srv-1"), 0))
        assertFalse(PlaybackResolveRetryPolicy.shouldRetry(ProviderException.Unknown("srv-1"), 0))
        assertFalse(PlaybackResolveRetryPolicy.shouldRetry(IllegalStateException("bug"), 0))
    }
}
