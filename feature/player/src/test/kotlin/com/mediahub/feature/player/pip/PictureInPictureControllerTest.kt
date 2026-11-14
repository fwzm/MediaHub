package com.mediahub.feature.player.pip

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Rational
import androidx.activity.ComponentActivity
import com.mediahub.feature.player.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * PiP 参数构造契约（条目 30 / 31 / 123 / 137）。
 *
 * 在 Robolectric 上验证「系统实际会读到的字段」：窗口比例、三个 RemoteAction、
 * API 31+ 的自动进入开关、以及退出播放器后必须关闭自动进入。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PictureInPictureControllerTest {

    private lateinit var activity: Activity
    private lateinit var controller: PictureInPictureController

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity.packageManager)
            .setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        controller = PictureInPictureController(activity)
    }

    @Test
    fun `device support follows the system feature`() {
        assertTrue(controller.isSupported())

        shadowOf(activity.packageManager)
            .setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, false)

        assertFalse(controller.isSupported())
        assertEquals(null, controller.buildParams(playingState()))
    }

    @Test
    fun `window aspect ratio follows the playing video`() {
        val params = controller.buildParams(playingState(videoWidth = 1920, videoHeight = 1080))

        assertNotNull(params)
        assertEquals(Rational(16, 9), params!!.aspectRatio)
    }

    @Test
    fun `window aspect ratio keeps subtitles at the same relative position in pip`() {
        val params = controller.buildParams(playingState(videoWidth = 1080, videoHeight = 1920))

        assertNotNull(params)
        assertEquals(
            "PiP 窗口必须与视频同比例，字幕底部比例才不会在切窗口后漂移",
            Rational(9, 16),
            params!!.aspectRatio,
        )
    }

    @Test
    fun `three remote actions are exposed while playing`() {
        val actions = controller.buildParams(playingState())!!.actions

        assertEquals(3, actions.size)
        assertEquals(activity.getString(R.string.pip_action_rewind), actions[0].title.toString())
        assertEquals(activity.getString(R.string.pip_action_pause), actions[1].title.toString())
        assertEquals(activity.getString(R.string.pip_action_forward), actions[2].title.toString())
        assertNotNull(actions[0].actionIntent)
    }

    @Test
    fun `window title follows the current episode`() {
        val params = controller.buildParams(playingState())

        assertEquals("第 1 集", params!!.title.toString())
    }

    @Test
    fun `paused playback exposes the play action`() {
        val actions = controller.buildParams(playingState(isPlaying = false))!!.actions

        assertEquals(activity.getString(R.string.pip_action_play), actions[1].title.toString())
    }

    @Test
    fun `auto enter follows the user preference on android 12 and above`() {
        assertTrue(controller.buildParams(playingState(autoPictureInPicture = true))!!.isAutoEnterEnabled)
        assertFalse(
            controller.buildParams(playingState(autoPictureInPicture = false))!!.isAutoEnterEnabled,
        )
    }

    @Test
    fun `paused playback does not auto enter`() {
        assertFalse(
            controller.buildParams(playingState(isPlaying = false))!!.isAutoEnterEnabled,
        )
    }

    @Test
    fun `leaving the player disables auto enter and drops actions`() {
        val params = controller.buildParams(
            playingState().copy(playerActive = false),
        )

        assertNotNull(params)
        assertFalse(params!!.isAutoEnterEnabled)
        assertTrue(params.actions.isEmpty())
    }

    private fun playingState(
        isPlaying: Boolean = true,
        autoPictureInPicture: Boolean = true,
        videoWidth: Int = 1920,
        videoHeight: Int = 1080,
    ) = PictureInPictureSessionState(
        playerActive = true,
        autoPictureInPicture = autoPictureInPicture,
        isPlaying = isPlaying,
        resolveReady = true,
        title = "第 1 集",
        positionMs = 10_000L,
        durationMs = 100_000L,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
    )
}
