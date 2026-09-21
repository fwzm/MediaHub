package com.mediahub.core.database.repository

import android.content.Context
import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.entity.ServerEndpointEntity
import com.mediahub.core.database.entity.ServerEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ServerRepository.updateEndpointQuality] 条件更新（Phase 1I review P2）：
 * 校验与写入同一事务——endpointId 对应线路的 URL 仍等于 expectedUrl 才写入；
 * 地址已变化 / endpoint 不存在 → 跳过，绝不重挑主线路承接旧测量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerRepositoryUpdateEndpointQualityTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ServerRepository

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ServerRepository(db)
        db.serverDao().upsert(
            ServerEntity(
                id = "srv-net", name = "Emby", type = "EMBY",
                isDefault = true, sortOrder = 0, createdAtEpochMs = 0,
            )
        )
        db.serverEndpointDao().upsertAll(
            listOf(
                ServerEndpointEntity(
                    id = "ep-1", serverId = "srv-net", name = "默认线路",
                    url = "https://saved.example", isPrimary = true,
                ),
                ServerEndpointEntity(
                    id = "ep-2", serverId = "srv-net", name = "备用线路",
                    url = "https://backup.example", isPrimary = false,
                ),
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun endpoint(id: String): ServerEndpointEntity = runBlocking {
        db.serverEndpointDao().getByServer("srv-net").first { it.id == id }
    }

    @Test
    fun `matching endpoint id and url writes quality to that endpoint`() = runBlocking {
        repository.updateEndpointQuality(
            serverId = "srv-net", endpointId = "ep-1", expectedUrl = "https://saved.example",
            apiLatencyMs = 42, mediaFirstByteMs = 100, throughputMbps = 2.5,
            protocol = "http/1.1", supportsRange = true, httpCode = 200,
        )
        val updated = endpoint("ep-1")
        assertEquals(42L, updated.lastApiLatencyMs)
        assertEquals("http/1.1", updated.lastProtocol)
        assertEquals(true, updated.lastSupportsRange)
        assertEquals(200, updated.lastHttpCode)
        assertNull("非目标线路不受影响", endpoint("ep-2").lastApiLatencyMs)
    }

    @Test
    fun `url changed during test skips write - no stale result on modified line`() = runBlocking {
        // 测试期间线路被改指其他地址
        db.serverEndpointDao().upsert(endpoint("ep-1").copy(url = "https://moved.example"))
        repository.updateEndpointQuality(
            serverId = "srv-net", endpointId = "ep-1", expectedUrl = "https://saved.example",
            apiLatencyMs = 42, mediaFirstByteMs = null, throughputMbps = null,
            protocol = null, supportsRange = null, httpCode = 200,
        )
        assertNull("旧测量不得落到已变更的线路上", endpoint("ep-1").lastApiLatencyMs)
    }

    @Test
    fun `unknown endpoint id skips write`() = runBlocking {
        repository.updateEndpointQuality(
            serverId = "srv-net", endpointId = "ep-missing", expectedUrl = "https://saved.example",
            apiLatencyMs = 42, mediaFirstByteMs = null, throughputMbps = null,
            protocol = null, supportsRange = null, httpCode = 200,
        )
        assertNull(endpoint("ep-1").lastApiLatencyMs)
        assertNull(endpoint("ep-2").lastApiLatencyMs)
    }

    @Test
    fun `wrong server rejects write even if endpoint id matches elsewhere`() = runBlocking {
        repository.updateEndpointQuality(
            serverId = "srv-other", endpointId = "ep-1", expectedUrl = "https://saved.example",
            apiLatencyMs = 42, mediaFirstByteMs = null, throughputMbps = null,
            protocol = null, supportsRange = null, httpCode = 200,
        )
        assertNull(endpoint("ep-1").lastApiLatencyMs)
    }
}
