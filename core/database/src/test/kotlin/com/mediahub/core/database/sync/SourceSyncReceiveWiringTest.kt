package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.AndroidSourceTypeMap
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.model.MediaServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-96：接收口只解码、只回显身份，一个字节都不写库 —— 写入必须发生在用户预览并逐条允许之后的 applyRound。
 *
 * 三条证据：
 * 1) 对照组：同一封黄金信封经**手写**的写库网关会落两条 —— 证明下面的零写入断言不是空信封或坏夹具造成的；
 * 2) 生产接法 SourceSyncImportGateways.acceptOnly() 收同一封信封：写入计数为 0，身份与信封原文都拿得到
 *    （界面正是靠信封原文进预览 → 逐条允许 → 应用）；
 * 3) 结构断言：两个接收服务都不再有任何能吃 AndroidSourceSyncService 的构造器。
 *
 * 第 3 条是三条里唯一不会随夹具腐化的：W6-96 删掉的那两个次构造器就是「一个参数就能绕过逐条审批」的载具，
 * 把它写在测试里，是为了让将来重新加回来的人先看到红灯。
 */
class SourceSyncReceiveWiringTest {

    @Test
    fun theGoldenEnvelopeIsWritableSoTheZeroWriteChecksBelowHaveTeeth() = runBlocking {
        val store = ImportFakeStore()
        val service = SourceSyncTransferImportService(writeThrough(AndroidSourceSyncService(store, DEVICE_ID)))

        val imported = imported(service.importEnvelopeBytes(goldenEnvelopeBytes()))

        assertEquals("黄金信封里有两条可写来源", 2, imported.result.appliedCount)
        assertTrue(imported.result.hasWrites)
        assertEquals(2, store.createCalls)
        assertEquals(2, store.snapshots.size)
    }

    @Test
    fun theProductionGatewayEchoesTheSameEnvelopeIdentityWithoutWriting() = runBlocking {
        val store = ImportFakeStore()
        val service = SourceSyncTransferImportService(SourceSyncImportGateways.acceptOnly())

        val imported = imported(service.importEnvelopeBytes(goldenEnvelopeBytes()))

        assertNull(imported.result.rejectedReason)
        assertEquals("device-a", imported.result.deviceId)
        assertNotNull(imported.result.snapshotId)
        assertNotNull("界面靠内容指纹判断这份信封收过没有", imported.result.envelopeContentHash)
        assertNotNull("信封原文必须回传，否则界面进不了预览", imported.envelopeJson)
        assertFalse("接收口不得写入", imported.result.hasWrites)
        assertEquals(0, imported.result.appliedCount)
        assertEquals(0, imported.result.totalCount)
        // 这个断言之所以不是空转：store 根本拿不到这条路径（能拿到它的载具已删除，结构断言见下一条）。
        assertEquals(0, store.createCalls)
        assertEquals(0, store.updateCalls)
        assertTrue(store.snapshots.isEmpty())
    }

    @Test
    fun noReceiveEntryPointCanBeHandedAWriteCapableService() {
        val writeCapable = AndroidSourceSyncService::class.java
        val receiveTypes = listOf(
            SourceSyncTransferImportService::class.java,
            SourceSyncListenerImportService::class.java,
        )
        for (type in receiveTypes) {
            val leaked = type.declaredConstructors.filter { constructor ->
                constructor.parameterTypes.any { parameter -> writeCapable.isAssignableFrom(parameter) }
            }
            assertTrue(
                type.simpleName + " 不得再提供能直接接 AndroidSourceSyncService 的构造器" +
                    "（W6-96 删除的写穿载具）；实际命中的参数表：" +
                    leaked.map { it.parameterTypes.joinToString(",") { parameter -> parameter.simpleName } },
                leaked.isEmpty(),
            )
        }
    }

    private fun imported(outcome: SourceSyncTransferImportOutcome): SourceSyncTransferImportOutcome.Imported {
        assertTrue("期待导入成功，实际 " + outcome.reasonOrNull, outcome.isImported)
        return outcome as SourceSyncTransferImportOutcome.Imported
    }

    /** W6-96 之前由次构造器提供的那条写库接线；现在只在测试里手写出来当对照。 */
    private fun writeThrough(service: AndroidSourceSyncService): SourceSyncImportGateway =
        SourceSyncImportGateway { envelopeJson -> service.importEnvelope(envelopeJson) }

    private fun goldenEnvelopeBytes(): ByteArray {
        val stream = javaClass.getResourceAsStream("/" + GOLDEN_RESOURCE)
        assertTrue("夹具不存在：" + GOLDEN_RESOURCE, stream != null)
        return stream!!.use { it.readBytes() }
    }

    /** 内存版 SourceSyncStore：只够导入服务用，记 create/update 调用数（与 SourceSyncTransferImportTest 同形）。 */
    private class ImportFakeStore : SourceSyncStore {
        val snapshots = LinkedHashMap<String, SourceSyncSourceSnapshot>()

        var createCalls = 0
            private set

        var updateCalls = 0
            private set

        private var sequence = 0

        override suspend fun listSourceSnapshots(): List<SourceSyncSourceSnapshot> = snapshots.values.toList()

        override suspend fun listLocalSources(): List<LocalSourceRecord> = snapshots.values.map { it.toLocalRecord() }

        override suspend fun findBySyncSourceId(syncSourceId: String): LocalSourceRecord? {
            val found = snapshots.values.firstOrNull { it.syncSourceId == syncSourceId } ?: return null
            return found.toLocalRecord()
        }

        override suspend fun createLocalSource(snapshot: SourceSyncSourceSnapshot): String {
            createCalls++
            sequence++
            val id = "srv-new-" + sequence
            snapshots[id] = snapshot.copy(serverId = id)
            return id
        }

        override suspend fun updateLocalSource(serverId: String, snapshot: SourceSyncSourceSnapshot): Boolean {
            updateCalls++
            if (!snapshots.containsKey(serverId)) return false
            snapshots[serverId] = snapshot.copy(serverId = serverId)
            return true
        }

        override suspend fun bindSyncIdentity(
            serverId: String,
            syncSourceId: String,
            recordRevision: Long,
            sourceTypeRaw: String?,
        ): Boolean = snapshots.containsKey(serverId)

        override fun observeServers(): Flow<List<MediaServer>> = flowOf(emptyList())

        override suspend fun getServer(id: String): MediaServer? = null

        private fun SourceSyncSourceSnapshot.toLocalRecord(): LocalSourceRecord {
            val resolvedType = sourceTypeRaw?.takeIf { it.isNotBlank() }
                ?.let { AndroidSourceTypeMap.toServerType(it) }
                ?: serverType
            val activeUrl = endpoints.firstOrNull { it.isPrimary && it.enabled }?.url
                ?: endpoints.firstOrNull { it.enabled }?.url
            return LocalSourceRecord(
                serverId = serverId,
                syncSourceId = syncSourceId,
                type = resolvedType,
                url = activeUrl,
                accountScope = accountScope,
                displayName = name,
                recordRevision = recordRevision,
                sourceTypeRaw = sourceTypeRaw,
            )
        }
    }

    private companion object {
        const val DEVICE_ID = "device-a"
        const val GOLDEN_RESOURCE = "sync/windows-envelope-device-a.json"
    }
}
