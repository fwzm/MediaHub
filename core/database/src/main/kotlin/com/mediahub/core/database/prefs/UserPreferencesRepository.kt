package com.mediahub.core.database.prefs

import com.mediahub.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * 用户偏好读写面（可测性抽象，Phase 1B-2.4）。
 * [UserPreferencesStore] 是唯一生产实现（DataStore 持久化）。
 */
interface UserPreferencesRepository {
    val flow: Flow<UserPreferences>
    suspend fun update(transform: (UserPreferences) -> UserPreferences)
}
