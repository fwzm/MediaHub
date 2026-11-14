package com.mediahub.feature.stats

import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.WatchStatsStore
import com.mediahub.model.UserPreferences
import com.mediahub.model.WatchReview
import com.mediahub.model.WatchSession
import com.mediahub.model.WatchStatsOverview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 条目 141/122：统计页状态（开关门控 + 快照透传）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStatsStore : WatchStatsStore {
        val overview = MutableStateFlow(WatchStatsOverview.Empty)

        override fun observeOverview(trendDays: Int): Flow<WatchStatsOverview> = overview

        override fun observeHistory(limit: Int): Flow<List<WatchSession>> = MutableStateFlow(emptyList())

        override suspend fun record(session: WatchSession) = Unit

        override suspend fun clearAll() = Unit
    }

    private class FakePreferences : UserPreferencesRepository {
        val state = MutableStateFlow(UserPreferences())
        override val flow: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    private fun CoroutineScope.subscribe(viewModel: StatsViewModel) = launch {
        viewModel.state.collect {}
    }

    @Test
    fun `state exposes overview and enabled flag`() = runTest {
        val store = FakeStatsStore()
        val preferences = FakePreferences()
        store.overview.value = WatchStatsOverview(
            totalWatchedMs = 7_200_000,
            movieCount = 3,
            episodeCount = 12,
            watchDayCount = 5,
            sessionCount = 9,
            review = WatchReview(totalWatchedMs = 7_200_000, topTitle = "测试电影"),
        )
        val viewModel = StatsViewModel(store, preferences)

        backgroundScope.subscribe(viewModel)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.enabled)
        assertEquals(7_200_000L, state.overview.totalWatchedMs)
        assertEquals(3, state.overview.movieCount)
        assertEquals("测试电影", state.overview.review.topTitle)
    }

    @Test
    fun `disabled preference is reflected without deleting existing overview`() = runTest {
        val store = FakeStatsStore()
        val preferences = FakePreferences()
        store.overview.value = WatchStatsOverview(totalWatchedMs = 60_000, sessionCount = 1)
        val viewModel = StatsViewModel(store, preferences)

        backgroundScope.subscribe(viewModel)
        preferences.state.value = UserPreferences(watchStatsEnabled = false)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.enabled)
        // 条目 122：关闭只停止记录，已有记录仍然可读。
        assertEquals(60_000L, state.overview.totalWatchedMs)
        assertEquals(1, state.overview.sessionCount)
    }
}
