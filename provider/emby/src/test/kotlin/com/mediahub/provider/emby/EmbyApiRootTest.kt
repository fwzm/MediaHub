package com.mediahub.provider.emby

import com.mediahub.provider.emby.api.EmbyApiRoot
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbyApiRootTest {
    @Test
    fun `api root preserves reverse proxy path and normalizes slashes`() {
        assertEquals(
            "https://example.test/emby/Users/Me",
            EmbyApiRoot.from(" https://example.test/emby/// ").endpoint("/Users/Me"),
        )
        assertEquals(
            "https://example.test/Users/Me",
            EmbyApiRoot.from("https://example.test/").endpoint("Users/Me"),
        )
    }
}
