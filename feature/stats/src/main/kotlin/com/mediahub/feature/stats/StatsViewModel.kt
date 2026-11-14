package com.mediahub.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.WatchStatsStore
import com.mediahub.model.WatchStatsOverview
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 统计页状态：开关（条目 122）+ 聚合快照（条目 141）。 */
data class StatsUiState(
    val enabled: Boolean = true,
    val overview: WatchStatsOverview = WatchStatsOverview.Empty,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    statsStore: WatchStatsStore,
    preferencesStore: UserPreferencesRepository,
) : ViewModel() {

    val state: StateFlow<StatsUiState> = combine(
        statsStore.observeOverview(TREND_DAYS),
        preferencesStore.flow,
    ) { overview, preferences ->
        StatsUiState(enabled = preferences.watchStatsEnabled, overview = overview)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private companion object {
        /** 趋势窗口：与统计仓库默认值一致，保持 30 天。 */
        const val TREND_DAYS = 30
    }
}
