package com.mediahub.provider.jellyfin.api

import com.mediahub.core.common.ClientIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 标准 Authorization 头 contract（ADR-039 冻结）：
 * `MediaBrowser Client/Device/DeviceId/Version[, Token]`；X-Emby-* 与 X-MediaBrowser-* 不使用。
 */
class JellyfinAuthorizationHeaderBuilderTest {

    private val builder = JellyfinAuthorizationHeaderBuilder(
        ClientIdentity(client = "MediaHub", device = "Pixel", deviceId = "dev-42", version = "1.2.3")
    )

    @Test
    fun `header name is standard authorization`() {
        assertEquals("Authorization", builder.headerName())
    }

    @Test
    fun `pre-login header carries identity without token`() {
        assertEquals(
            "MediaBrowser Client=\"MediaHub\", Device=\"Pixel\", DeviceId=\"dev-42\", Version=\"1.2.3\"",
            builder.build(),
        )
    }

    @Test
    fun `authenticated header appends token in media browser format`() {
        assertEquals(
            "MediaBrowser Client=\"MediaHub\", Device=\"Pixel\", DeviceId=\"dev-42\", Version=\"1.2.3\", Token=\"tok-9\"",
            builder.build("tok-9"),
        )
    }

    @Test
    fun `blank token is treated as absent`() {
        assertEquals(
            "MediaBrowser Client=\"MediaHub\", Device=\"Pixel\", DeviceId=\"dev-42\", Version=\"1.2.3\"",
            builder.build("  "),
        )
    }
}
