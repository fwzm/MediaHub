package com.mediahub.feature.server

import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Existing-server 修复模式判定（评审 Patch 3）。 */
class ServerEditModePolicyTest {

    private fun descriptor(id: String, category: ProviderCategory) = ProviderDescriptor(
        providerId = id,
        displayName = id,
        description = "",
        category = category,
        capabilities = setOf(ProviderCapability.AUTH),
        authMethod = AuthMethod.USERNAME_PASSWORD,
        status = ProviderStatus.AVAILABLE,
    )

    @Test
    fun `auth provider maps to AUTH_RELOGIN`() {
        assertEquals(
            ExistingServerMode.AUTH_RELOGIN,
            ServerEditModePolicy.modeFor(descriptor("emby", ProviderCategory.MEDIA_SERVER)),
        )
    }

    @Test
    fun `local storage maps to LOCAL_REAUTHORIZE`() {
        assertEquals(
            ExistingServerMode.LOCAL_REAUTHORIZE,
            ServerEditModePolicy.modeFor(descriptor("local", ProviderCategory.LOCAL_STORAGE)),
        )
    }
}
