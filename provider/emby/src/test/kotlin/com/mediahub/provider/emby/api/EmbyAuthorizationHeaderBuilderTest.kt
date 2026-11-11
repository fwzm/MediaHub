package com.mediahub.provider.emby.api

import com.mediahub.core.common.ClientIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EmbyAuthorizationHeaderBuilderTest {
    @Test
    fun ordinaryIdentityPreservesExistingHeaderContract() {
        val builder = EmbyAuthorizationHeaderBuilder(ClientIdentity("MediaHub", "Android", "device-1", "0.1"))
        assertEquals("X-Emby-Authorization", builder.headerName())
        assertEquals("Emby UserId=\"u1\", Client=\"MediaHub\", Device=\"Android\", DeviceId=\"device-1\", Version=\"0.1\"",
            builder.build("u1"))
        assertFalse(builder.build("u1").contains("Token"))
    }

    @Test
    fun everyQuotedIdentityFieldEscapesQuotesAndBackslashes() {
        val builder = EmbyAuthorizationHeaderBuilder(
            ClientIdentity("App\"A\\B", "Device\"C\\D", "ID\"E\\F", "V\"G\\H"),
        )
        assertEquals("Emby UserId=\"U\\\"9\\\\Z\", Client=\"App\\\"A\\\\B\", Device=\"Device\\\"C\\\\D\", DeviceId=\"ID\\\"E\\\\F\", Version=\"V\\\"G\\\\H\"",
            builder.build("U\"9\\Z"))
    }

    @Test
    fun absentOrBlankUserIdDoesNotCreateAnIdentityField() {
        val builder = EmbyAuthorizationHeaderBuilder(ClientIdentity("MediaHub", "Android", "device-1", "0.1"))
        for (userId in listOf(null, "", " \t ")) {
            assertEquals("Emby Client=\"MediaHub\", Device=\"Android\", DeviceId=\"device-1\", Version=\"0.1\"",
                builder.build(userId))
        }
    }
}
