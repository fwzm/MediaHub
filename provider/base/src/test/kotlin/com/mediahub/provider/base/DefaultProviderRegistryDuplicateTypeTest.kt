package com.mediahub.provider.base

import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaBrowseProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderFactory
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import com.mediahub.provider.api.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 注册表对重复注册的处置（ADR-005/ADR-015 的装配期不变量）。
 *
 * 基线实现已经会用 require 在重复 serverType / 重复 provider id 时装配期失败（见
 * DefaultProviderRegistryTest 的两个 duplicate 用例），但错误信息是固定串
 * "Duplicate provider server type"：既不说是哪个 ServerType，也不说冲突双方是谁，
 * 装配方只能逐个模块翻 @IntoSet 绑定去猜。
 * 本测试锁定「失败信息必须可诊断」：点名冲突类型 + 双方 provider id，并按类型名稳定排序。
 */
class DefaultProviderRegistryDuplicateTypeTest {

    private fun descriptor(id: String, type: ServerType, name: String) = ProviderDescriptor(
        id = id,
        serverType = type,
        displayName = name,
        category = ProviderCategory.CLOUD_STORAGE,
        declaredCapabilities = setOf(
            com.mediahub.provider.api.ProviderCapability.BROWSE,
            com.mediahub.provider.api.ProviderCapability.PLAYBACK,
        ),
        authMethod = AuthMethod.NONE,
        status = ProviderStatus.STABLE,
    )

    private class FakeProvider(
        override val serverId: String,
        override val type: ServerType,
        override val displayName: String,
        override val descriptor: ProviderDescriptor,
    ) : MediaProvider, MediaBrowseProvider, MediaPlaybackProvider {
        override suspend fun testConnection() = ConnectionStatus(ok = true, message = "ok")
        override suspend fun listFolder(
            folder: com.mediahub.model.MediaItem?,
            page: com.mediahub.model.PageRequest,
        ) = com.mediahub.model.PagedResult<com.mediahub.model.MediaItem>(emptyList())
        override suspend fun resolvePlayback(
            item: com.mediahub.model.MediaItem,
            options: com.mediahub.model.PlaybackOptions,
        ) = com.mediahub.model.PlaybackSource(url = "file:///x")
    }

    private class FakeFactory(override val descriptor: ProviderDescriptor) : MediaProviderFactory {
        override fun create(server: MediaServer): ProviderHandle {
            val provider = FakeProvider(
                serverId = server.id,
                type = descriptor.serverType,
                displayName = server.displayName,
                descriptor = descriptor,
            )
            return ProviderHandle(provider = provider, browse = provider, playback = provider)
        }
    }

    private fun server(type: ServerType, id: String = "s1") = MediaServer(
        id = id,
        name = "测试",
        type = type,
        baseUrl = "http://localhost",
        createdAtEpochMs = 0,
    )

    @Test
    fun `duplicate server type fails fast instead of silently dropping a provider`() {
        try {
            DefaultProviderRegistry(
                setOf(
                    FakeFactory(descriptor("emby-a", ServerType.EMBY, "Emby A")),
                    FakeFactory(descriptor("emby-b", ServerType.EMBY, "Emby B")),
                )
            )
            fail("重复 serverType 必须装配期失败，而不是静默保留其中一个")
        } catch (expected: IllegalArgumentException) {
            val message = expected.message.orEmpty()
            assertTrue("错误信息应点名冲突的数据源类型：$message", message.contains("EMBY"))
            assertTrue("错误信息应列出双方 provider id：$message", message.contains("emby-a"))
            assertTrue("错误信息应列出双方 provider id：$message", message.contains("emby-b"))
        }
    }

    @Test
    fun `duplicate detection does not depend on declaration order`() {
        try {
            DefaultProviderRegistry(
                setOf(
                    FakeFactory(descriptor("zeta", ServerType.LOCAL, "Zeta")),
                    FakeFactory(descriptor("alpha", ServerType.LOCAL, "Alpha")),
                )
            )
            fail("重复 serverType 必须装配期失败（与声明顺序无关）")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("LOCAL"))
        }
    }

    @Test
    fun `duplicate provider id fails fast and names both ids`() {
        try {
            DefaultProviderRegistry(
                setOf(
                    FakeFactory(descriptor("shared-id", ServerType.EMBY, "Emby A")),
                    FakeFactory(descriptor("shared-id", ServerType.LOCAL, "本地 B")),
                )
            )
            fail("provider id 重复必须装配期失败（id 是稳定标识，不能两人共用）")
        } catch (expected: IllegalArgumentException) {
            val message = expected.message.orEmpty()
            assertTrue("错误信息应点名冲突的 provider id：$message", message.contains("shared-id"))
        }
    }

    @Test
    fun `distinct server types still build a working registry`() {
        val registry = DefaultProviderRegistry(
            setOf(
                FakeFactory(descriptor("emby", ServerType.EMBY, "Emby")),
                FakeFactory(descriptor("local", ServerType.LOCAL, "本地")),
            )
        )
        assertEquals(setOf(ServerType.EMBY, ServerType.LOCAL), registry.supportedTypes)
        assertNotNull(registry.create(server(ServerType.EMBY)))
        assertNotNull(registry.create(server(ServerType.LOCAL)))
        assertEquals(2, registry.descriptors().size)
    }

    @Test
    fun `empty factory set is still allowed`() {
        val registry = DefaultProviderRegistry(emptySet())
        assertTrue(registry.supportedTypes.isEmpty())
        assertTrue(registry.descriptors().isEmpty())
    }
}
