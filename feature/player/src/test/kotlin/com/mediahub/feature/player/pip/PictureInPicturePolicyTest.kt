package com.mediahub.feature.player.pip

import com.mediahub.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画中画（条目 30 / 31 / 113 / 123 / 137）的纯策略契约。
 *
 * 这些规则决定了「能不能进 PiP」「后台是否自动进」「窗口比例」「窗口内动作」，
 * 不依赖 Activity 与真机，因此可以在 JVM 上先红后绿地钉住。
 */
class PictureInPicturePolicyTest {

    @Test
    fun `supported only from api 26 with the device feature`() {
        assertFalse(PictureInPicturePolicy.isSupported(sdkInt = 25, hasSystemFeature = true))
        assertFalse(PictureInPicturePolicy.isSupported(sdkInt = 34, hasSystemFeature = false))
        assertTrue(PictureInPicturePolicy.isSupported(sdkInt = 26, hasSystemFeature = true))
        assertTrue(PictureInPicturePolicy.isSupported(sdkInt = 36, hasSystemFeature = true))
    }

    @Test
    fun `background auto enter requires playing ready enabled supported and not already in pip`() {
        fun decide(
            supported: Boolean = true,
            autoPictureInPicture: Boolean = true,
            isPlaying: Boolean = true,
            resolveReady: Boolean = true,
            alreadyInPictureInPicture: Boolean = false,
        ): Boolean = PictureInPicturePolicy.shouldAutoEnter(
            supported = supported,
            autoPictureInPicture = autoPictureInPicture,
            isPlaying = isPlaying,
            resolveReady = resolveReady,
            alreadyInPictureInPicture = alreadyInPictureInPicture,
        )

        assertTrue(decide())
        assertFalse("开关关闭时不得自动进入", decide(autoPictureInPicture = false))
        assertFalse("暂停时不得自动进入", decide(isPlaying = false))
        assertFalse("解析未完成时不得自动进入", decide(resolveReady = false))
        assertFalse("不支持 PiP 的设备不得自动进入", decide(supported = false))
        assertFalse("已经在 PiP 中不得重复进入", decide(alreadyInPictureInPicture = true))
    }

    @Test
    fun `user leave hint fallback only covers api 26 to 30`() {
        fun fallback(sdkInt: Int): Boolean = PictureInPicturePolicy.shouldEnterOnUserLeaveHint(
            sdkInt = sdkInt,
            supported = true,
            autoPictureInPicture = true,
            isPlaying = true,
            resolveReady = true,
            alreadyInPictureInPicture = false,
        )

        assertTrue(fallback(26))
        assertTrue(fallback(30))
        // API 31+ 由 setAutoEnterEnabled 交给系统接管，不能再用 onUserLeaveHint 重复进入。
        assertFalse(fallback(31))
        assertFalse(fallback(36))
    }

    @Test
    fun `enter button is offered only when supported and the source is ready`() {
        assertTrue(PictureInPicturePolicy.canShowEnterButton(supported = true, resolveReady = true))
        assertFalse(PictureInPicturePolicy.canShowEnterButton(supported = true, resolveReady = false))
        assertFalse(PictureInPicturePolicy.canShowEnterButton(supported = false, resolveReady = true))
    }

    @Test
    fun `aspect ratio follows the video and falls back for unknown sizes`() {
        assertEquals(PipAspectRatio(16, 9), PictureInPicturePolicy.aspectRatio(1920, 1080))
        assertEquals(PipAspectRatio(9, 16), PictureInPicturePolicy.aspectRatio(1080, 1920))
        assertEquals(PipAspectRatio(4, 3), PictureInPicturePolicy.aspectRatio(1440, 1080))
        assertEquals(
            "未知尺寸回退 16:9，避免 PiP 窗口退化成正方形",
            PipAspectRatio(16, 9),
            PictureInPicturePolicy.aspectRatio(0, 0),
        )
        assertEquals(PipAspectRatio(16, 9), PictureInPicturePolicy.aspectRatio(-1, 1080))
    }

    @Test
    fun `aspect ratio is clamped to the system supported range`() {
        val ultraWide = PictureInPicturePolicy.aspectRatio(3840, 1080)
        assertTrue(ultraWide.ratio <= PictureInPicturePolicy.MAX_ASPECT_RATIO + 0.001f)
        assertEquals(PipAspectRatio(239, 100), ultraWide)

        val ultraTall = PictureInPicturePolicy.aspectRatio(1080, 3840)
        assertEquals(PipAspectRatio(100, 239), ultraTall)
        assertTrue(ultraTall.ratio >= PictureInPicturePolicy.MIN_ASPECT_RATIO - 0.001f)
    }

    @Test
    fun `remote actions are rewind toggle and forward in order`() {
        assertEquals(
            listOf(PipCommand.REWIND, PipCommand.TOGGLE_PLAY_PAUSE, PipCommand.FORWARD),
            PictureInPicturePolicy.commands(),
        )
    }

    @Test
    fun `seek commands clamp to the media bounds`() {
        assertEquals(
            15_000L,
            PictureInPicturePolicy.seekTargetMs(25_000L, 100_000L, PipCommand.REWIND),
        )
        assertEquals(
            "快退不得越过片头",
            0L,
            PictureInPicturePolicy.seekTargetMs(5_000L, 100_000L, PipCommand.REWIND),
        )
        assertEquals(
            "快进不得越过片尾",
            100_000L,
            PictureInPicturePolicy.seekTargetMs(95_000L, 100_000L, PipCommand.FORWARD),
        )
        assertEquals(
            "时长未知时仍允许相对快进，不做无法证明的上界裁剪",
            105_000L,
            PictureInPicturePolicy.seekTargetMs(95_000L, 0L, PipCommand.FORWARD),
        )
        assertNull(
            "播放/暂停不是 seek，不得返回位置",
            PictureInPicturePolicy.seekTargetMs(95_000L, 100_000L, PipCommand.TOGGLE_PLAY_PAUSE),
        )
    }

    @Test
    fun `next episode continuation stays inside the viewer preference`() {
        assertTrue(
            PictureInPicturePolicy.allowsAutoAdvance(
                autoPlayNextEpisode = true,
                itemType = MediaType.EPISODE,
            ),
        )
        assertFalse(
            PictureInPicturePolicy.allowsAutoAdvance(
                autoPlayNextEpisode = false,
                itemType = MediaType.EPISODE,
            ),
        )
        assertFalse(
            PictureInPicturePolicy.allowsAutoAdvance(
                autoPlayNextEpisode = true,
                itemType = MediaType.MOVIE,
            ),
        )
        assertFalse(
            PictureInPicturePolicy.allowsAutoAdvance(
                autoPlayNextEpisode = true,
                itemType = null,
            ),
        )
    }

    @Test
    fun `command names round trip and unknown names are ignored`() {
        assertEquals(PipCommand.REWIND, PictureInPicturePolicy.commandFor("REWIND"))
        assertEquals(PipCommand.TOGGLE_PLAY_PAUSE, PictureInPicturePolicy.commandFor("TOGGLE_PLAY_PAUSE"))
        assertEquals(PipCommand.FORWARD, PictureInPicturePolicy.commandFor("FORWARD"))
        assertNull(PictureInPicturePolicy.commandFor("FROM_FUTURE"))
        assertNull(PictureInPicturePolicy.commandFor(null))
    }

    @Test
    fun `params refresh only for fields the system renders`() {
        val base = PictureInPictureSessionState(
            playerActive = true,
            autoPictureInPicture = true,
            isPlaying = true,
            resolveReady = true,
            title = "第 1 集",
            positionMs = 10_000L,
            durationMs = 100_000L,
            videoWidth = 1920,
            videoHeight = 1080,
        )

        assertFalse(
            "位置每帧变化不能反复 setPictureInPictureParams",
            PictureInPicturePolicy.shouldRefreshParams(base, base.copy(positionMs = 11_000L)),
        )
        assertTrue(PictureInPicturePolicy.shouldRefreshParams(base, base.copy(title = "第 2 集")))
        assertTrue(PictureInPicturePolicy.shouldRefreshParams(base, base.copy(isPlaying = false)))
        assertTrue(PictureInPicturePolicy.shouldRefreshParams(base, base.copy(playerActive = false)))
        assertTrue(
            PictureInPicturePolicy.shouldRefreshParams(
                base,
                base.copy(videoWidth = 1080, videoHeight = 1920),
            ),
        )
        assertTrue(
            "离开 PiP 后必须重新打开自动进入，否则回全屏后再退后台就不会进窗口了",
            PictureInPicturePolicy.shouldRefreshParams(
                base.copy(inPictureInPicture = true),
                base.copy(inPictureInPicture = false),
            ),
        )
    }
}
