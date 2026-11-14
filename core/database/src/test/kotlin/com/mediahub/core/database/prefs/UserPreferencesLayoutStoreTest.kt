package com.mediahub.core.database.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.mediahub.model.HomeSectionIds
import com.mediahub.model.HomeSectionStyle
import com.mediahub.model.UserPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/** 条目 3/4/56 的持久化往返：默认值、写入、重开 DataStore 后仍可读。 */
class UserPreferencesLayoutStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var store: UserPreferencesStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File(temporaryFolder.root, "layout.preferences_pb")
        store = UserPreferencesStore(newDataStore())
    }

    private fun newDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { file },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `layout defaults are empty and play button is shown`() = runBlocking {
        val prefs = store.flow.first()
        assertTrue(prefs.homeSectionStyles.isEmpty())
        assertTrue(prefs.libraryOrder.isEmpty())
        assertTrue(prefs.showPlayerPlayButton)
    }

    @Test
    fun `home section styles persist independently`() = runBlocking {
        store.update {
            it.copy(
                homeSectionStyles = mapOf(
                    HomeSectionIds.CONTINUE_WATCHING to HomeSectionStyle.ROW,
                    HomeSectionIds.SERVERS to HomeSectionStyle.COMPACT,
                ),
            )
        }
        val prefs = store.flow.first()
        assertEquals(HomeSectionStyle.ROW, prefs.homeSectionStyles[HomeSectionIds.CONTINUE_WATCHING])
        assertEquals(HomeSectionStyle.COMPACT, prefs.homeSectionStyles[HomeSectionIds.SERVERS])
    }

    @Test
    fun `library order and play button persist`() = runBlocking {
        store.update {
            it.copy(
                libraryOrder = listOf("lib-b", "lib-a"),
                showPlayerPlayButton = false,
            )
        }
        val prefs = store.flow.first()
        assertEquals(listOf("lib-b", "lib-a"), prefs.libraryOrder)
        assertFalse(prefs.showPlayerPlayButton)
    }

    @Test
    fun `writing defaults clears layout preferences`() = runBlocking {
        store.update {
            it.copy(
                homeSectionStyles = mapOf(HomeSectionIds.SERVERS to HomeSectionStyle.ROW),
                libraryOrder = listOf("lib-a"),
            )
        }
        store.update { UserPreferences() }
        val prefs = store.flow.first()
        assertEquals(emptyMap<String, HomeSectionStyle>(), prefs.homeSectionStyles)
        assertTrue(prefs.libraryOrder.isEmpty())
    }
}
