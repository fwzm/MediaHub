package com.mediahub.feature.home

import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerType
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 首页「继续观看」的畸形数据兜底（用户条目 89 / 审计 79）。
 *
 * 兼容服写入的进度可能缺 serverId/itemId —— 点击会合成 `detail//…` 这类非法路由并在导航时抛异常；
 * 同一 (serverId,itemId) 重复出现则会让 `LazyRow(items, key = …)` 的 key 冲突直接崩溃。
 * 单条跳过即可，其余记录必须照常展示。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeContinueWatchingSanitizeTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private class FakeServerStore : ServerStore {
        override fun observeServers(): Flow<List<MediaServer>> = flowOf(emptyList())
        override suspend fun getServer(id: String): MediaServer? = null
    }

    private class FakeProgressStore(private val rows: List<PlaybackProgress>) : ProgressStore {
        override fun observeContinueWatching(limit: Int): Flow<List<PlaybackProgress>> = flowOf(rows)
        override suspend fun getResume(serverId: String, itemId: String): Long? = null
        override suspend fun save(progress: PlaybackProgress) = Unit
        override suspend fun get(serverId: String, itemId: String): PlaybackProgress? = null
        override suspend fun clearAll(): Int = 0
    }

    private class FakeProvider : MediaProvider {
        override val serverId: String = "srv-1"
        override val type: ServerType = ServerType.EMBY
        override val displayName: String = "Emby"
        override val descriptor = ProviderDescriptor(
            id = "emby", serverType = ServerType.EMBY, displayName = "Emby",
            category = com.mediahub.provider.api.ProviderCategory.MEDIA_SERVER,
            declaredCapabilities = emptySet(),
            authMethod = com.mediahub.provider.api.AuthMethod.USERNAME_PASSWORD,
            status = com.mediahub.provider.api.ProviderStatus.STABLE,
        )
        override suspend fun testConnection(): com.mediahub.provider.api.ConnectionStatus =
            com.mediahub.provider.api.ConnectionStatus(ok = true)
    }

    private class FakeRegistry : MediaProviderRegistry {
        override fun factoryFor(type: ServerType) = null
        override val supportedTypes: Set<ServerType> = emptySet()
        override fun create(server: MediaServer): ProviderHandle = ProviderHandle(provider = FakeProvider())
        override fun descriptors() = emptyList<ProviderDescriptor>()
    }

    private val noOpLogger = object : Logger {
        override fun d(tag: LogTag, message: String) = Unit
        override fun i(tag: LogTag, message: String) = Unit
        override fun w(tag: LogTag, message: String, throwable: Throwable?) = Unit
        override fun e(tag: LogTag, message: String, throwable: Throwable?) = Unit
    }

    private fun progress(
        serverId: String,
        itemId: String,
        updatedAt: Long,
    ) = PlaybackProgress(
        serverId = serverId, itemId = itemId, positionMs = 60_000, durationMs = 600_000,
        isPaused = false, updatedAtEpochMs = updatedAt, itemTitle = "条目",
    )

    @Test
    fun `rows without server or item id are skipped and duplicates collapse to one key`() =
        runTest(dispatcher) {
            val rows = listOf(
                progress("srv-1", "item-1", updatedAt = 3),
                progress("srv-1", "item-1", updatedAt = 2), // 同一 key 的重复行
                progress("", "item-2", updatedAt = 1),      // 缺 serverId：非法详情路由
                progress("srv-2", "", updatedAt = 0),       // 缺 itemId
            )
            val vm = HomeViewModel(
                FakeServerStore(), FakeProgressStore(rows), FakeRegistry(), noOpLogger,
            )
            val job = launch { vm.continueWatching.collect { } }
            advanceUntilIdle()

            assertEquals(
                listOf("srv-1" to "item-1"),
                vm.continueWatching.value.map { it.serverId to it.itemId },
            )
            job.cancel()
        }
}
