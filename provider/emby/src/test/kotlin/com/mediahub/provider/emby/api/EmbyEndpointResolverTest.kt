package com.mediahub.provider.emby.api

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbyEndpointResolverTest {
    @Test
    fun bareHostUsesEmbyPrefix() {
        for (base in listOf("https://fixture.invalid", "https://fixture.invalid/")) {
            assertEquals("https://fixture.invalid/emby/Users/u1",
                EmbyEndpointResolver(base).endpoint("/Users/u1"))
        }
    }

    @Test
    fun existingPrefixAndOuterWhitespaceAreNormalized() {
        assertEquals("https://fixture.invalid/emby/Users/u1",
            EmbyEndpointResolver(" \thttps://fixture.invalid/emby/// \n").endpoint("/Users/u1"))
    }

    @Test
    fun reverseProxyPathIsPreservedWhileOuterWhitespaceIsTrimmed() {
        assertEquals("https://fixture.invalid/proxy/emby/Users/u1",
            EmbyEndpointResolver(" https://fixture.invalid/proxy/// ").endpoint("/Users/u1"))
    }

    @Test
    fun endpointAcceptsPathWithoutLeadingSlash() {
        assertEquals("https://fixture.invalid/emby/Users/u1",
            EmbyEndpointResolver("https://fixture.invalid").endpoint("Users/u1"))
    }

    @Test
    fun endpointUsesOneSeparatorForMultipleLeadingSlashes() {
        assertEquals("https://fixture.invalid/emby/Users/u1",
            EmbyEndpointResolver("https://fixture.invalid").endpoint("///Users/u1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankBaseIsRejected() {
        EmbyEndpointResolver(" \t\n ")
    }
}
