package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.database.entity.ServerEntity
import com.mediahub.core.database.sync.ServerSyncMappers.applySyncIdentity
import com.mediahub.core.database.sync.ServerSyncMappers.sourceType
import com.mediahub.core.database.sync.ServerSyncMappers.toLocalSourceRecord
import com.mediahub.core.database.sync.ServerSyncMappers.toSyncIdentity
import com.mediahub.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W6-B：servers 表 <-> 同步规划视图的双向映射（纯 JVM，无 Android 依赖）。
 *
 * 重点在 D3 的"未知 wire 类型不得丢弃/降级"：原始值优先、解析不出来时 type 记 null
 * 而不是回退成某个枚举（旧 EntityMappers 遇到未知 type 会降级成 LOCAL，本条链路不能那样做）。
 */
class ServerSyncMappersTest {

    private fun entity(
        id: String = "srv-1",
        name: String = "客厅 Emby",
        type: String = "EMBY",
        username: String? = "alice",
        syncSourceId: String? = "local-abc",
        recordRevision: Long = 3L,
        sourceTypeRaw: String? = "emby",
    ) = ServerEntity(
        id = id,
        name = name,
        type = type,
        username = username,
        createdAtEpochMs = 0,
        syncSourceId = syncSourceId,
        syncRecordRevision = recordRevision,
        syncSourceTypeRaw = sourceTypeRaw,
    )

    @Test
    fun `entity with sync identity maps to a planning record`() {
        val record = entity().toLocalSourceRecord(primaryUrl = "http://nas:8096/emby")

        assertEquals("srv-1", record.serverId)
        assertEquals("local-abc", record.syncSourceId)
        assertEquals(3L, record.recordRevision)
        assertEquals("emby", record.sourceTypeRaw)
        assertEquals(ServerType.EMBY, record.type)
        assertEquals("http://nas:8096/emby", record.url)
        assertEquals("客厅 Emby", record.displayName)
        assertEquals("alice", record.accountScope)
    }

    @Test
    fun `blank username and sync identity become null`() {
        val record = entity(username = "   ", syncSourceId = "  ", sourceTypeRaw = "").toLocalSourceRecord()

        assertNull(record.accountScope)
        assertNull(record.syncSourceId)
        assertNull(record.sourceTypeRaw)
    }

    @Test
    fun `blank or missing primary url becomes null`() {
        assertNull(entity().toLocalSourceRecord(primaryUrl = "  ").url)
        assertNull(entity().toLocalSourceRecord(primaryUrl = null).url)
    }

    @Test
    fun `known raw wire value resolves to a server type`() {
        // type 列写的是 LOCAL，但原始 wire 是 fn-nas：raw 优先
        val record = entity(type = "LOCAL", sourceTypeRaw = "fn-nas").toLocalSourceRecord()

        assertEquals(ServerType.FN_NAS, record.type)
        assertEquals("fn-nas", record.sourceTypeRaw)
    }

    @Test
    fun `unknown raw wire value keeps raw and yields null type`() {
        val record = entity(type = "LOCAL", sourceTypeRaw = "quantum-nas").toLocalSourceRecord()

        assertNull("未知 wire 不得降级成枚举值", record.type)
        assertEquals("quantum-nas", record.sourceTypeRaw)
    }

    @Test
    fun `legacy entity without raw falls back to the type column`() {
        val record = entity(type = "JELLYFIN", sourceTypeRaw = null).toLocalSourceRecord()

        assertEquals(ServerType.JELLYFIN, record.type)
        assertNull(record.sourceTypeRaw)
    }

    @Test
    fun `unknown type column without raw yields null type`() {
        val record = entity(type = "QUANTUM", sourceTypeRaw = null).toLocalSourceRecord()

        assertNull("未知枚举名不得被当成已知类型", record.type)
        assertNull(entity(type = "QUANTUM", sourceTypeRaw = null).sourceType())
    }

    @Test
    fun `to sync identity fills raw from a known type`() {
        val identity = LocalSourceRecord(
            serverId = "srv-1",
            syncSourceId = " local-xyz ",
            type = ServerType.WEBDAV,
            recordRevision = 9L,
        ).toSyncIdentity()

        assertEquals("local-xyz", identity.syncSourceId)
        assertEquals(9L, identity.recordRevision)
        assertEquals("webdav", identity.sourceTypeRaw)
    }

    @Test
    fun `to sync identity keeps an explicit unknown raw`() {
        val identity = LocalSourceRecord(
            serverId = "srv-q",
            syncSourceId = "local-q",
            type = null,
            recordRevision = 1L,
            sourceTypeRaw = "quantum-nas",
        ).toSyncIdentity()

        assertEquals("quantum-nas", identity.sourceTypeRaw)
    }

    @Test
    fun `apply sync identity only touches the three sync columns`() {
        val original = entity(name = "旧名字", type = "EMBY", username = "bob")

        val updated = original.applySyncIdentity(
            ServerSyncIdentity(
                syncSourceId = "local-new",
                recordRevision = 12L,
                sourceTypeRaw = "jellyfin",
            ),
        )

        assertEquals("local-new", updated.syncSourceId)
        assertEquals(12L, updated.syncRecordRevision)
        assertEquals("jellyfin", updated.syncSourceTypeRaw)
        // 其余列逐字保持
        assertEquals("旧名字", updated.name)
        assertEquals("EMBY", updated.type)
        assertEquals("bob", updated.username)
        assertEquals(original.copy(
            syncSourceId = "local-new",
            syncRecordRevision = 12L,
            syncSourceTypeRaw = "jellyfin",
        ), updated)
    }
}
