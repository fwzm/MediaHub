package com.mediahub.core.database.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.mediahub.model.ProfessionalInfoPreferences
import com.mediahub.model.UserPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 专业信息偏好（P1 专业/精简开关）持久化测试：
 * 空库默认值（专业）、写入读回、重入（DataStore/scope 重建后）读回、
 * 无关偏好更新不丢字段、原始键落盘值。
 */
class ProfessionalInfoPreferencesStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: UserPreferencesStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = newStore()
        store = UserPreferencesStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun storeFile(): File = File(temporaryFolder.root, "user.preferences_pb")

    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { storeFile() },
    )

    /**
     * 重入：取消旧 scope（DataStore 要求同文件单实例）→ 新 scope + 新 DataStore 实例
     * 读同一磁盘文件（模拟进程重启后再次进入）。
     */
    private suspend fun reopenStoreOnSameFile() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = newStore()
        store = UserPreferencesStore(dataStore)
    }

    @Test
    fun `empty store exposes professional info default (expert mode on)`() = runBlocking {
        val preferences = store.flow.first()

        assertEquals(ProfessionalInfoPreferences.Default, preferences.professionalInfo)
        assertEquals(UserPreferences().professionalInfo, preferences.professionalInfo)
        assertTrue(preferences.professionalInfo.expertMode)
    }

    @Test
    fun `expert mode off survives write read-back and re-entry`() = runBlocking {
        store.update { current ->
            current.copy(professionalInfo = current.professionalInfo.copy(expertMode = false))
        }
        assertFalse(store.flow.first().professionalInfo.expertMode)

        reopenStoreOnSameFile()

        val restored = store.flow.first()
        assertFalse(restored.professionalInfo.expertMode)
        assertEquals(
            ProfessionalInfoPreferences(expertMode = false),
            restored.professionalInfo,
        )
    }

    @Test
    fun `unrelated preference update preserves professional info`() = runBlocking {
        store.update { current ->
            current.copy(professionalInfo = current.professionalInfo.copy(expertMode = false))
        }

        store.update { current -> current.copy(subtitleSizeSp = 27, autoLandscape = false) }

        val restored = store.flow.first()
        assertEquals(27, restored.subtitleSizeSp)
        assertFalse(restored.autoLandscape)
        assertFalse(restored.professionalInfo.expertMode)
    }

    @Test
    fun `raw persisted value lands on the expert mode key`() = runBlocking {
        store.update { current ->
            current.copy(professionalInfo = current.professionalInfo.copy(expertMode = false))
        }

        val raw = dataStore.data.first()
        assertEquals(false, raw[UserPreferencesStore.Keys.PROFESSIONAL_INFO_EXPERT_MODE])
    }
}
