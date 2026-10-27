package com.mediahub.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.sync.ISourceSyncBaselineStore
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesStore: UserPreferencesRepository,
    /**
     * 已配对设备页的同步基线出口（W6-80）：忘记一台设备时要连同该对端的基线一起清掉，
     * 否则同一 deviceId 重新配对后会被当成「已经同步过」，对端真实变更被静默跳过。
     * 设置页只是把这一个出口转交给 PairedDeviceRoute。
     */
    val syncBaselineStore: ISourceSyncBaselineStore,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesStore.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserPreferences())

    fun update(transform: (UserPreferences) -> UserPreferences) {
        viewModelScope.launch { preferencesStore.update(transform) }
    }

    fun resetPlayerVisualEffects() {
        viewModelScope.launch { preferencesStore.resetPlayerVisualEffects() }
    }
}
