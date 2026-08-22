package com.mediahub.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerIconTest {

    @Test
    fun nullIconMapsToNullModel() {
        assertNull(serverIconModel(null))
        assertNull(serverIconModel(""))
        assertNull(serverIconModel("   "))
    }

    @Test
    fun builtinIconMapsToNull() {
        assertNull(serverIconModel("builtin://emby"))
        assertNull(serverIconModel("builtin://jellyfin"))
    }

    @Test
    fun fileIconMapsToFile() {
        val model = serverIconModel("file:///data/user/0/app/files/server_icons/srv1.webp")
        assertEquals(File("/data/user/0/app/files/server_icons/srv1.webp"), model)
    }

    @Test
    fun otherIconMapsToRawString() {
        assertEquals("https://example.com/icon.png", serverIconModel("https://example.com/icon.png"))
    }
}