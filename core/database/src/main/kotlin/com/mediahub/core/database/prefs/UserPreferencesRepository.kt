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

    /** 一次性读取当前持久化偏好（DataStore 已缓存时即时返回，供进入播放器时同步取自动横屏/沉浸式开关）。 */
    suspend fun snapshot(): UserPreferences
}
