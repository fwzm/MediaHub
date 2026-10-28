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
import com.mediahub.core.database.sync.SourceSyncRemovalStep
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.StoredToken
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.provider.api.SessionStoreCleaner
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W6-88：删除链八步里「卡在哪一步」必须说得出来。
 *
 * 为什么单独立用例：`ServerSourceSyncRemover` 只能看到 [RemoveServerUseCase] 抛了什么。如果删除链把八个
 * 步骤的异常都原样抛（或都折成同一个 RuntimeException），跨端同步那边就只能报一句
 * `local-source-remove-failed`——用户凭据可能还在，却不知道该重试删除还是去来源页手动清。
 * 这里用真 Room 内存库 + 真 [RemoveServerUseCase]，让第 4 步与第 8 步分别失败，验证：
 * 1. 抛出的异常带的是**那一步**的步骤码；
 * 2. 失败之前的步骤真的做了（不是一步没走就报错）；
 * 3. 失败之后的步骤真的没做（不假装清完了）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoveServerUseCaseStepsTest {

    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun a_broken_session_store_names_the_last_step_and_lets_the_first_seven_finish() = runBlocking {
        val storage = MapSecretStorage()
        val harness = harness(storage = storage, cleaner = ThrowingSessionCleaner())
        val server = seedSource(harness.repository, "A")
        seedLocalState(harness, server)

        val failure = removeFailingWith(harness.useCase, server)

        assertEquals(SourceSyncRemovalStep.SESSION_STORE, failure.step)
        assertEquals("remove-server-failed:session-store", failure.message)
        // 前七步真的做了：否则「第八步失败」这句话就是假的。
        assertNull(harness.db.serverDao().getById(server.id))
        assertNull(harness.db.accountDao().getForServer(server.id))
        assertNull(harness.tokens.readTokens(server.id))
        assertFalse(harness.vault.contains(server.id, CredentialVault.CredentialKind.PASSWORD))
        assertTrue(harness.db.playbackProgressDao().getAll().none { it.serverId == server.id })
        assertTrue(harness.db.subtitleMemoryDao().getAll().none { it.serverId == server.id })
        assertFalse(harness.icons.iconFile(server.id).exists())
        // 第八步没做成：会话清理抛了，只调了一次。
        assertEquals(1, (harness.cleaner as ThrowingSessionCleaner).calls)
    }

    @Test
    fun a_credential_failure_stops_the_chain_at_the_fourth_step_and_leaves_the_rest_alone() = runBlocking {
        val harness = harness(storage = CredentialBreakingStorage(), cleaner = RecordingSessionCleaner())
        val server = seedSource(harness.repository, "A")
        seedLocalState(harness, server)

        val failure = removeFailingWith(harness.useCase, server)

        assertEquals(SourceSyncRemovalStep.CREDENTIALS, failure.step)
        assertTrue(failure.message!!.endsWith(":credentials"))
        // 前三步做了
        assertNull(harness.db.serverDao().getById(server.id))
        assertNull(harness.db.accountDao().getForServer(server.id))
        assertNull(harness.tokens.readTokens(server.id))
        // 第三步之后的都还没做：进度、字幕、图标、会话都还在，凭据也还在（clear 抛在第一个 remove 上）。
        assertEquals(1, harness.db.playbackProgressDao().getAll().count { it.serverId == server.id })
        assertEquals(1, harness.db.subtitleMemoryDao().getAll().count { it.serverId == server.id })
        assertTrue(harness.icons.iconFile(server.id).exists())
        assertEquals(emptyList<String>(), (harness.cleaner as RecordingSessionCleaner).cleared)
    }

    // ------------------------------------------------------------ 夹具

    private class Harness(
        val db: AppDatabase,
        val repository: ServerRepository,
        val icons: ServerIconStore,
        val tokens: TokenStore,
        val vault: CredentialVault,
        val useCase: RemoveServerUseCase,
        val cleaner: SessionStoreCleaner,
    )

    private fun harness(
        storage: SecretStorage,
        cleaner: SessionStoreCleaner,
    ): Harness {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        db = database
        val repository = ServerRepository(database)
        val icons = ServerIconStore(RuntimeEnvironment.getApplication())
        val tokens = TokenStore(storage)
        val vault = CredentialVault(storage)
        val useCase = RemoveServerUseCase(
            serverRepository = repository,
            progressRepository = ProgressRepository(database),
            subtitleMemoryRepository = SubtitleMemoryRepository(database),
            accountRepository = AccountRepository(database),
            tokenStore = tokens,
            credentialVault = vault,
            serverIconStore = icons,
            sessionStoreCleaner = cleaner,
        )
        return Harness(database, repository, icons, tokens, vault, useCase, cleaner)
    }

    private suspend fun removeFailingWith(
        useCase: RemoveServerUseCase,
        server: MediaServer,
    ): RemoveServerFailedException {
        val failure = try {
            useCase.remove(server)
            null
        } catch (error: RemoveServerFailedException) {
            error
        }
        assertNotNull("删除链没有抛步骤异常", failure)
        return failure!!
    }

    private suspend fun seedSource(repository: ServerRepository, name: String): MediaServer {
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
        return created
    }

    private suspend fun seedLocalState(harness: Harness, server: MediaServer) {
        harness.db.accountDao().upsert(
            AccountEntity(
                id = "acc-" + server.id,
                serverId = server.id,
                userId = "user-" + server.id,
                displayName = "账号 " + server.name,
                authState = "AUTHENTICATED",
                authenticatedAtEpochMs = 1_650_000_000_000L,
            ),
        )
        harness.db.playbackProgressDao().upsert(
            PlaybackProgressEntity(
                serverId = server.id,
                itemId = "item-" + server.id,
                positionMs = 60_000L,
                durationMs = 300_000L,
                isPaused = false,
                updatedAtEpochMs = 1_650_000_000_000L,
            ),
        )
        harness.db.subtitleMemoryDao().upsert(
            SubtitleMemoryEntity(
                versionKey = "vk-" + server.id,
                serverId = server.id,
                subtitleId = "sub-" + server.id,
                offsetMs = 250L,
                updatedAtEpochMs = 1_650_000_000_000L,
            ),
        )
        harness.icons.iconFile(server.id).apply {
            parentFile?.mkdirs()
            writeText("icon-bytes:" + server.id)
        }
        harness.tokens.saveTokens(server.id, StoredToken(accessToken = "access-" + server.id))
        harness.vault.save(server.id, CredentialVault.CredentialKind.PASSWORD, "pw-" + server.id)
    }

    /** 测试用内存密文槽（真实现是 KeystoreSecretStorage，Robolectric 下不可用）。 */
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

    /** 凭据柜写不进去的存储：只有 `cred:` 前缀的删除会炸（token 用的是另一套 key）。 */
    private class CredentialBreakingStorage : SecretStorage {
        private val values = LinkedHashMap<String, String>()

        override suspend fun put(key: String, value: String) {
            values[key] = value
        }

        override suspend fun get(key: String): String? = values[key]

        override suspend fun remove(key: String) {
            if (key.startsWith("cred:")) throw IOException("keyring unavailable")
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

    private class ThrowingSessionCleaner : SessionStoreCleaner {
        var calls = 0

        override suspend fun clear(serverId: String) {
            calls++
            throw IOException("session store closed")
        }
    }
}
