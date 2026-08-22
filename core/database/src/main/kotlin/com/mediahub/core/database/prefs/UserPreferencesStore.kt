package com.mediahub.core.database.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mediahub.model.SubtitleStyle
import com.mediahub.model.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userPrefsDataStore by preferencesDataStore(name = "user_prefs")

/** 用户偏好持久化（DataStore）。 */
@Singleton
class UserPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : UserPreferencesRepository {

    override val flow: Flow<UserPreferences> = context.userPrefsDataStore.data.map { prefs ->
        UserPreferences(
            defaultPlaybackSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1f,
            subtitleSizeSp = prefs[Keys.SUBTITLE_SIZE] ?: 18,
            enableHardwareDecoding = prefs[Keys.HW_DECODING] ?: true,
            preferDirectPlay = prefs[Keys.PREFER_DIRECT_PLAY] ?: true,
            autoPlayNextEpisode = prefs[Keys.AUTO_NEXT] ?: true,
            maxBitrateBps = prefs[Keys.MAX_BITRATE],
            showPlayerInfoOverlay = prefs[Keys.SHOW_INFO] ?: false,
            autoLandscape = prefs[Keys.AUTO_LANDSCAPE] ?: true,
            immersiveBars = prefs[Keys.IMMERSIVE_BARS] ?: true,
            subtitleStyle = readSubtitleStyle(prefs),
        )
    }

    override suspend fun snapshot(): UserPreferences = flow.first()

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        context.userPrefsDataStore.edit { prefs ->
            val current = UserPreferences(
                defaultPlaybackSpeed = prefs[Keys.DEFAULT_SPEED] ?: 1f,
                subtitleSizeSp = prefs[Keys.SUBTITLE_SIZE] ?: 18,
                enableHardwareDecoding = prefs[Keys.HW_DECODING] ?: true,
                preferDirectPlay = prefs[Keys.PREFER_DIRECT_PLAY] ?: true,
                autoPlayNextEpisode = prefs[Keys.AUTO_NEXT] ?: true,
                maxBitrateBps = prefs[Keys.MAX_BITRATE],
                showPlayerInfoOverlay = prefs[Keys.SHOW_INFO] ?: false,
                autoLandscape = prefs[Keys.AUTO_LANDSCAPE] ?: true,
                immersiveBars = prefs[Keys.IMMERSIVE_BARS] ?: true,
                subtitleStyle = readSubtitleStyle(prefs),
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
            prefs[Keys.AUTO_LANDSCAPE] = updated.autoLandscape
            prefs[Keys.IMMERSIVE_BARS] = updated.immersiveBars
            writeSubtitleStyle(prefs, updated.subtitleStyle)
        }
    }

    private fun readSubtitleStyle(prefs: androidx.datastore.preferences.core.Preferences): SubtitleStyle =
        SubtitleStyle(
            textColor = prefs[Keys.SUB_TEXT_COLOR] ?: 0xFFFFFFFF.toInt(),
            backgroundColor = prefs[Keys.SUB_BG_COLOR] ?: 0x00000000,
            edgeType = prefs[Keys.SUB_EDGE_TYPE] ?: SubtitleStyle.EDGE_TYPE_OUTLINE,
            edgeColor = prefs[Keys.SUB_EDGE_COLOR] ?: 0xFF000000.toInt(),
            textScale = prefs[Keys.SUB_TEXT_SCALE] ?: 1f,
            bottomPaddingFraction = prefs[Keys.SUB_BOTTOM_PADDING] ?: 0.08f,
            applyEmbeddedStyles = prefs[Keys.SUB_APPLY_EMBEDDED] ?: true,
        )

    private fun writeSubtitleStyle(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        style: SubtitleStyle,
    ) {
        prefs[Keys.SUB_TEXT_COLOR] = style.textColor
        prefs[Keys.SUB_BG_COLOR] = style.backgroundColor
        prefs[Keys.SUB_EDGE_TYPE] = style.edgeType
        prefs[Keys.SUB_EDGE_COLOR] = style.edgeColor
        prefs[Keys.SUB_TEXT_SCALE] = style.textScale
        prefs[Keys.SUB_BOTTOM_PADDING] = style.bottomPaddingFraction
        prefs[Keys.SUB_APPLY_EMBEDDED] = style.applyEmbeddedStyles
    }

    private object Keys {
        val DEFAULT_SPEED = floatPreferencesKey("default_playback_speed")
        val SUBTITLE_SIZE = intPreferencesKey("subtitle_size_sp")
        val HW_DECODING = booleanPreferencesKey("enable_hardware_decoding")
        val PREFER_DIRECT_PLAY = booleanPreferencesKey("prefer_direct_play")
        val AUTO_NEXT = booleanPreferencesKey("auto_play_next_episode")
        val MAX_BITRATE = longPreferencesKey("max_bitrate_bps")
        val SHOW_INFO = booleanPreferencesKey("show_player_info_overlay")
        val AUTO_LANDSCAPE = booleanPreferencesKey("auto_landscape")
        val IMMERSIVE_BARS = booleanPreferencesKey("immersive_bars")
        val SUB_TEXT_COLOR = intPreferencesKey("subtitle_text_color")
        val SUB_BG_COLOR = intPreferencesKey("subtitle_background_color")
        val SUB_EDGE_TYPE = intPreferencesKey("subtitle_edge_type")
        val SUB_EDGE_COLOR = intPreferencesKey("subtitle_edge_color")
        val SUB_TEXT_SCALE = floatPreferencesKey("subtitle_text_scale")
        val SUB_BOTTOM_PADDING = floatPreferencesKey("subtitle_bottom_padding_fraction")
        val SUB_APPLY_EMBEDDED = booleanPreferencesKey("subtitle_apply_embedded_styles")
    }
}
