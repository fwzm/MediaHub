package com.mediahub.core.database.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediahub.model.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.userPrefsDataStore by preferencesDataStore(name = "user_prefs")

/** 用户偏好持久化（DataStore）。 */
@Singleton
class UserPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val flow: Flow<UserPreferences> = context.userPrefsDataStore.data.map { prefs ->
        UserPreferences(
            defaultPlaybackSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1f,
            subtitleSizeSp = prefs[Keys.SUBTITLE_SIZE] ?: 18,
            enableHardwareDecoding = prefs[Keys.HW_DECODING] ?: true,
            preferDirectPlay = prefs[Keys.PREFER_DIRECT_PLAY] ?: true,
            autoPlayNextEpisode = prefs[Keys.AUTO_NEXT] ?: true,
            maxBitrateBps = prefs[Keys.MAX_BITRATE],
            showPlayerInfoOverlay = prefs[Keys.SHOW_INFO] ?: false,
        )
    }

    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        context.userPrefsDataStore.edit { prefs ->
            val current = UserPreferences(
                defaultPlaybackSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1f,
                subtitleSizeSp = prefs[Keys.SUBTITLE_SIZE] ?: 18,
                enableHardwareDecoding = prefs[Keys.HW_DECODING] ?: true,
                preferDirectPlay = prefs[Keys.PREFER_DIRECT_PLAY] ?: true,
                autoPlayNextEpisode = prefs[Keys.AUTO_NEXT] ?: true,
                maxBitrateBps = prefs[Keys.MAX_BITRATE],
                showPlayerInfoOverlay = prefs[Keys.SHOW_INFO] ?: false,
            )
            val updated = transform(current)
            prefs[Keys.DEFAULT_SPEED] = updated.defaultPlaybackSpeed
            prefs[Keys.SUBTITLE_SIZE] = updated.subtitleSizeSp
            prefs[Keys.HW_DECODING] = updated.enableHardwareDecoding
            prefs[Keys.PREFER_DIRECT_PLAY] = updated.preferDirectPlay
            prefs[Keys.AUTO_NEXT] = updated.autoPlayNextEpisode
            updated.maxBitrateBps?.let { prefs[Keys.MAX_BITRATE] = it }
                ?: prefs.remove(Keys.MAX_BITRATE)
            prefs[Keys.SHOW_INFO] = updated.showPlayerInfoOverlay
        }
    }

    private object Keys {
        val DEFAULT_SPEED = floatPreferencesKey("default_playback_speed")
        val SUBTITLE_SIZE = intPreferencesKey("subtitle_size_sp")
        val HW_DECODING = booleanPreferencesKey("enable_hardware_decoding")
        val PREFER_DIRECT_PLAY = booleanPreferencesKey("prefer_direct_play")
        val AUTO_NEXT = booleanPreferencesKey("auto_play_next_episode")
        val MAX_BITRATE = longPreferencesKey("max_bitrate_bps")
        val SHOW_INFO = booleanPreferencesKey("show_player_info_overlay")
    }
}
