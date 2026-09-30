package com.mediahub.feature.settings

import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.ProfessionalInfoPreferences
import com.mediahub.model.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        val state = MutableStateFlow(UserPreferences())
        override val flow: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    /** preferences 是 WhileSubscribed 的 StateFlow：测试必须先订阅，上游才会被收集。
     *  订阅挂 backgroundScope（测试体结束时自动取消，不会卡住 runTest）。 */
    private fun CoroutineScope.subscribePreferences(viewModel: SettingsViewModel) = launch {
        viewModel.preferences.collect {}
    }

    @Test
    fun `initial preferences expose the professional default (expert mode on)`() = runTest(dispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = SettingsViewModel(repository)
        backgroundScope.subscribePreferences(viewModel)
        runCurrent()

        assertTrue(viewModel.preferences.value.professionalInfo.expertMode)
        assertEquals(ProfessionalInfoPreferences.Default, viewModel.preferences.value.professionalInfo)
    }

    @Test
    fun `toggling professional info persists through the repository and reads back`() = runTest(dispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = SettingsViewModel(repository)
        backgroundScope.subscribePreferences(viewModel)
        runCurrent()

        // 设置页开关路径：SettingsScreen 调 viewModel.update copy professionalInfo
        viewModel.update { p ->
            p.copy(professionalInfo = p.professionalInfo.copy(expertMode = false))
        }
        runCurrent()

        assertFalse(viewModel.preferences.value.professionalInfo.expertMode)
        assertFalse(repository.state.value.professionalInfo.expertMode)
        // 其他字段不被该更新触碰
        assertTrue(repository.state.value.enableHardwareDecoding)
        assertEquals(UserPreferences().playbackEngineMode, repository.state.value.playbackEngineMode)
    }

    @Test
    fun `toggling back on restores expert density`() = runTest(dispatcher) {
        val repository = FakeUserPreferencesRepository()
        val viewModel = SettingsViewModel(repository)
        backgroundScope.subscribePreferences(viewModel)
        runCurrent()

        viewModel.update { p ->
            p.copy(professionalInfo = p.professionalInfo.copy(expertMode = false))
        }
        runCurrent()
        viewModel.update { p ->
            p.copy(professionalInfo = p.professionalInfo.copy(expertMode = true))
        }
        runCurrent()

        assertTrue(repository.state.value.professionalInfo.expertMode)
    }
}
