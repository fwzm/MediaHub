package com.mediahub.provider.emby

import com.mediahub.provider.emby.api.EmbyApiRoot
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbyApiRootTest {

    @Test
    fun `bare host gets emby prefix`() {
        assertEquals(
            "https://example.test/emby/Users/user-1",
            EmbyApiRoot.from("https://example.test/").endpoint("Users/user-1"),
        )
        assertEquals(
            "https://example.test/emby/Users/user-1",
            EmbyApiRoot.from("https://example.test").endpoint("Users/user-1"),
        )
    }

    @Test
    fun `already-emby path does not duplicate prefix`() {
        assertEquals(
            "https://example.test/emby/Users/user-1",
            EmbyApiRoot.from("https://example.test/emby").endpoint("Users/user-1"),
        )
        assertEquals(
            "https://example.test/emby/Users/user-1",
            EmbyApiRoot.from(" https://example.test/emby/// ").endpoint("/Users/user-1"),
        )
    }

    @Test
    fun `current user uses Users by id not Me`() {
        // 官方 UserService Reference：GET /Users/{Id}，不用未文档化的 /Users/Me
        assertEquals(
            "https://example.test/emby/Users/user-1",
            EmbyApiRoot.from("https://example.test/").endpoint("Users/user-1"),
        )
    }
}
