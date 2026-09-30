package com.mediahub.core.database.prefs

import com.mediahub.model.PlayerVisualEffectsPreferences
import com.mediahub.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * 用户偏好读写面（可测性抽象，Phase 1B-2.4）。
 * [UserPreferencesStore] 是唯一生产实现（DataStore 持久化）。
 */
interface UserPreferencesRepository {
    val flow: Flow<UserPreferences>

    /**
     * 原子更新完整偏好快照。实现必须让 [transform] 在存储当前值上执行，避免并发写丢字段。
     */
    suspend fun update(transform: (UserPreferences) -> UserPreferences)

    /** 原子更新视觉效果子树，同时保留字幕、手势和播放内核等其他用户偏好。 */
    suspend fun updatePlayerVisualEffects(
        transform: (PlayerVisualEffectsPreferences) -> PlayerVisualEffectsPreferences,
    ) {
        update { current ->
            current.copy(
                playerVisualEffects = transform(current.playerVisualEffects).normalized(),
            )
        }
    }

    /** 恢复当前版本的播放器视觉效果默认值。 */
    suspend fun resetPlayerVisualEffects() {
        update { current ->
            current.copy(playerVisualEffects = PlayerVisualEffectsPreferences.Default)
        }
    }
}
