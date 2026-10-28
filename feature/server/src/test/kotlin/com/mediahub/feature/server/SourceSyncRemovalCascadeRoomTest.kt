package com.mediahub.feature.server

import androidx.room.Room
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.entity.AccountEntity
import com.mediahub.core.database.entity.PlaybackProgressEntity
import com.mediahub.core.database.entity.SubtitleMemoryEntity
import com.mediahub.core.database.repository.AccountRepository
import com.mediahub.core.database.repository.ProgressRepository
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.database.repository.SubtitleMemoryRepository
import com.mediahub.core.database.sync.AndroidSourceSyncService
import com.mediahub.core.database.sync.SourceSyncRemoveOutcome
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.provider.api.SessionStoreCleaner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W6-87：对端声明删除时**真的**级联清理（Robolectric + 真 Room 内存库 + 真 [RemoveServerUseCase]）。
 *
 * 缺口来历：`ServerSourceSyncRemoverTest` 用 fake handler 只证明「调没调删除链」，
 * `RemoveServerUseCase` 此前没有任何测试文件，`AndroidSourceSyncServiceRoomTest` 也没跑过删除。
 * 于是「墓碑 → 删来源」这条路上「换成了只删一行」的回归，三处测试都拦不住。
 *
 * 判据分层（写断言时别弄反）：`accounts` 有 FK CASCADE，删 servers 行就会跟着没 ⇒ 这条断言没有鉴别力，
 * 只是用户看得见的那一面；真正能被「只删一行」漏掉的是 playback_progress / subtitle_memory /
 * 图标文件 / token / 凭据 / provider 会话六处，负向对照也正打这里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceSyncRemovalCascadeRoomTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ServerRepository
    private lateinit var icons: ServerIconStore
    private lateinit var tokens: TokenStore
    private lateinit var vault: CredentialVault
    private lateinit var sessions: RecordingSessionCleaner
    private lateinit var useCase: RemoveServerUseCase
    private lateinit var remover: ServerSourceSyncRemover

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ServerRepository(db)
        icons = ServerIconStore(RuntimeEnvironment.getApplication())
        val storage = MapSecretStorage()
        tokens = TokenStore(storage)
        vault = CredentialVault(storage)
        sessions = RecordingSessionCleaner()
        useCase = RemoveServerUseCase(
            serverRepository = repository,
            progressRepository = ProgressRepository(db),
            subtitleMemoryRepository = SubtitleMemoryRepository(db),
            accountRepository = AccountRepository(db),
            tokenStore = tokens,
            credentialVault = vault,
            serverIconStore = icons,
            sessionStoreCleaner = sessions,
        )
        remover = ServerSourceSyncRemover(repository, useCase)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun removing_a_default_source_clears_every_piece_of_its_state_and_reselects_the_default() =
        runBlocking {
            val kept = seedSource(name = "B", syncSourceId = "local-bbb", revision = 7L)
            val doomed = seedSource(name = "A", syncSourceId = "local-aaa", revision = 3L)
            seedLocalState(kept)
            seedLocalState(doomed)
            // addServer 让第一台自动成为默认；显式把默认设成将被删的那台，才验得到「重选首条」。
            repository.setDefault(doomed.id)
            assertTrue(db.serverDao().getById(doomed.id)!!.isDefault)

            assertEquals(SourceSyncRemoveOutcome.Removed, remover.removeSource(doomed.id))

            assertNull(db.serverDao().getById(doomed.id))
            assertEquals(1, db.serverDao().count())
            assertTrue(db.serverEndpointDao().getByServer(doomed.id).isEmpty())
            assertEquals(listOf(kept.id), db.serverEndpointDao().getAll().map { it.serverId })
            assertNull(db.accountDao().getForServer(doomed.id))

            // 六处「只删一行」漏得掉的状态
            assertTrue(db.playbackProgressDao().getAll().none { it.serverId == doomed.id })
            assertTrue(db.subtitleMemoryDao().getAll().none { it.serverId == doomed.id })
            assertFalse(icons.iconFile(doomed.id).exists())
            assertNull(tokens.readTokens(doomed.id))
            assertFalse(vault.contains(doomed.id, CredentialVault.CredentialKind.PASSWORD))
            assertEquals(listOf(doomed.id), sessions.cleared)

            // 另一台一个字节都没动，连同步身份三列都还在
            val keptEntity = db.serverDao().getById(kept.id)!!
            assertEquals("local-bbb", keptEntity.syncSourceId)
            assertEquals(7L, keptEntity.syncRecordRevision)
            assertTrue(keptEntity.isDefault)
            assertEquals(1, db.serverDao().getAll().count { it.isDefault })
            assertEquals(1, db.serverEndpointDao().getByServer(kept.id).size)
            assertNotNull(db.accountDao().getForServer(kept.id))
            assertEquals(1, db.playbackProgressDao().getAll().count { it.serverId == kept.id })
            assertEquals(1, db.subtitleMemoryDao().getAll().count { it.serverId == kept.id })
            assertTrue(icons.iconFile(kept.id).exists())
            assertNotNull(tokens.readTokens(kept.id))
            assertTrue(vault.contains(kept.id, CredentialVault.CredentialKind.PASSWORD))
            assertEquals(1, db.serverDao().getAll().count())
        }

    @Test
    fun a_peer_declared_deletion_runs_the_real_cascade_through_the_sync_layer() =
        runBlocking {
            val doomed = seedSource(name = "A", syncSourceId = "local-aaa", revision = 3L)
            seedLocalState(doomed)
            val service = AndroidSourceSyncService(
                store = repository,
                deviceId = "device-android",
                snapshotIdFactory = { "snap-del" },
                clock = { 1_700_000_000_000L },
                sourceRemover = remover,
            )

            val report = service.applyRound(
                envelopeJson = deletionEnvelope("local-aaa", revision = 4L),
                allowedSyncSourceIds = listOf("local-aaa"),
            )

            assertEquals(1, report.applied)
            assertEquals(0, report.refused)
            assertTrue(report.removalFailures.isEmpty())
            assertTrue(report.refusals.isEmpty())
            assertEquals(listOf(doomed.id), report.removedServerIds)
            assertEquals(listOf("local-aaa"), report.removedSyncSourceIds)
            assertTrue(report.wrote.isEmpty())

            assertNull(db.serverDao().getById(doomed.id))
            assertEquals(0, db.serverDao().count())
            assertTrue(db.serverEndpointDao().getByServer(doomed.id).isEmpty())
            assertTrue(db.playbackProgressDao().getAll().isEmpty())
            assertTrue(db.subtitleMemoryDao().getAll().isEmpty())
            assertFalse(icons.iconFile(doomed.id).exists())
            assertNull(tokens.readTokens(doomed.id))
            assertFalse(vault.contains(doomed.id, CredentialVault.CredentialKind.PASSWORD))
            assertEquals(listOf(doomed.id), sessions.cleared)
        }

    private fun seedSource(name: String, syncSourceId: String, revision: Long): MediaServer = runBlocking {
        val created = repository.addServer(
            MediaServer(
                id = "",
                name = name,
                type = ServerType.EMBY,
                username = "alice",
                createdAtEpochMs = 1_600_000_000_000L,
                endpoints = listOf(
                    ServerEndpoint(
                        id = "",
                        serverId = "",
                        name = "主线路",
                        url = "http://nas-" + name.lowercase() + ":8096/emby",
                        isPrimary = true,
                        enabled = true,
                        sortOrder = 0,
                    ),
                ),
            ),
        )
        repository.bindSyncSourceId(created.id, syncSourceId, revision, "emby")
        created
    }

    private fun seedLocalState(server: MediaServer) = runBlocking {
        db.accountDao().upsert(
            AccountEntity(
                id = "acc-" + server.id,
                serverId = server.id,
                userId = "user-" + server.id,
                displayName = "账号 " + server.name,
                authState = "AUTHENTICATED",
                authenticatedAtEpochMs = 1_650_000_000_000L,
            ),
        )
        db.playbackProgressDao().upsert(
            PlaybackProgressEntity(
                serverId = server.id,
                itemId = "item-" + server.id,
                positionMs = 60_000L,
                durationMs = 300_000L,
                isPaused = false,
                updatedAtEpochMs = 1_650_000_000_000L,
            ),
        )
        db.subtitleMemoryDao().upsert(
            SubtitleMemoryEntity(
                versionKey = "vk-" + server.id,
                serverId = server.id,
                subtitleId = "sub-" + server.id,
                offsetMs = 250L,
                updatedAtEpochMs = 1_650_000_000_000L,
            ),
        )
        icons.iconFile(server.id).apply {
            parentFile?.mkdirs()
            writeText("icon-bytes:" + server.id)
        }
        tokens.saveTokens(server.id, StoredToken(accessToken = "access-" + server.id))
        vault.save(server.id, CredentialVault.CredentialKind.PASSWORD, "pw-" + server.id)
    }

    private fun deletionEnvelope(syncSourceId: String, revision: Long): String =
        "{\"schemaVersion\":1,\"minimumReaderVersion\":1,\"deviceId\":\"device-windows\"," +
            "\"snapshotId\":\"snap-del\",\"createdAtEpochMs\":1700000000000,\"capabilities\":{}," +
            "\"sources\":[],\"deletions\":[{\"syncSourceId\":\"" + syncSourceId + "\"," +
            "\"deletedAtEpochMs\":1700000000000,\"recordRevision\":" + revision + "}]}"

    /** 测试用内存密文槽（真实实现是 KeystoreSecretStorage，Robolectric 下不可用）。 */
    private class MapSecretStorage : SecretStorage {
        private val values = LinkedHashMap<String, String>()

        override suspend fun put(key: String, value: String) {
            values[key] = value
        }

        override suspend fun get(key: String): String? = values[key]

        override suspend fun remove(key: String) {
            values.remove(key)
        }

        override suspend fun contains(key: String): Boolean = values.containsKey(key)
    }

    private class RecordingSessionCleaner : SessionStoreCleaner {
        val cleared = mutableListOf<String>()

        override suspend fun clear(serverId: String) {
            cleared.add(serverId)
        }
    }
}
