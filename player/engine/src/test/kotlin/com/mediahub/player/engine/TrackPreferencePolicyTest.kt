package com.mediahub.player.engine

import android.os.LocaleList
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR-048 默认选轨策略的参数推导与接线。
 *
 * 选择结果本身由 [DefaultTrackSelectionPolicyTest] 用真实 `DefaultTrackSelector` 钉死；
 * 这里只保证"系统 LocaleList → 偏好语言参数"的收敛规则与生产接线没有分叉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackPreferencePolicyTest {

    private val appContext: android.content.Context =
        requireNotNull(RuntimeEnvironment.getApplication())

    @Test
    fun `language tags collapse to ordered deduped language codes`() {
        assertEquals(
            listOf("zh", "en"),
            TrackPreferencePolicy.audioLanguages(listOf("zh-Hans-CN", "en-US", "zh-Hant-TW")),
        )
    }

    @Test
    fun `blank tags never become a preference`() {
        assertEquals(
            listOf("de"),
            TrackPreferencePolicy.audioLanguages(listOf("", "  ", "de-DE")),
        )
    }

    @Test
    fun `empty tag list means no preference and leaves the selector untouched`() {
        val selector = TrackPreferencePolicy.prepare(
            androidx.media3.exoplayer.trackselection.DefaultTrackSelector(appContext),
            emptyList(),
        )
        assertTrue(selector.parameters.preferredAudioLanguages.isEmpty())
    }

    @Test
    fun `subtitle preference collapses to a single text language`() {
        assertEquals(listOf("zh"), TrackPreferencePolicy.textLanguages("zh-Hans-CN"))
        assertEquals(emptyList<String>(), TrackPreferencePolicy.textLanguages(null))
        assertEquals(emptyList<String>(), TrackPreferencePolicy.textLanguages("  "))
    }

    @Test
    fun `audio and subtitle preferences are both written into the selector`() {
        val selector = TrackPreferencePolicy.prepare(
            androidx.media3.exoplayer.trackselection.DefaultTrackSelector(appContext),
            listOf("en"),
            listOf("zh"),
        )
        assertEquals(listOf("en"), selector.parameters.preferredAudioLanguages.toList())
        assertEquals(listOf("zh"), selector.parameters.preferredTextLanguages.toList())
    }

    @Test
    fun `empty subtitle preference clears any previous text preference`() {
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(appContext)
        TrackPreferencePolicy.prepare(selector, listOf("en"), listOf("zh"))
        TrackPreferencePolicy.prepare(selector, listOf("en"), emptyList())
        assertTrue(selector.parameters.preferredTextLanguages.isEmpty())
    }

    @Test
    fun `production player factory seeds the selector with the system language preference`() {
        val previous = LocaleList.getDefault()
        try {
            LocaleList.setDefault(
                LocaleList(Locale.forLanguageTag("zh-Hans-CN"), Locale.forLanguageTag("en-US")),
            )
            val factory = PlayerFactory(appContext, MediaCacheProvider(appContext))
            assertEquals(
                listOf("zh", "en"),
                factory.createTrackSelector().parameters.preferredAudioLanguages.toList(),
            )
        } finally {
            LocaleList.setDefault(previous)
        }
    }
}
