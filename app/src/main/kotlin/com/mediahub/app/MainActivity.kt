package com.mediahub.app

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mediahub.app.navigation.MediaHubNavHost
import com.mediahub.app.ui.theme.MediaHubTheme
import com.mediahub.feature.player.pip.PictureInPictureController
import com.mediahub.feature.player.pip.PictureInPicturePolicy
import com.mediahub.feature.player.pip.PictureInPictureSession
import com.mediahub.feature.player.pip.PictureInPictureSessionState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * 单 Activity 宿主。
 *
 * 画中画（条目 30 / 31 / 113 / 123）的系统入口只能落在 Activity 上：
 * - API 31+：把 `setAutoEnterEnabled` 写进 PiP 参数，退到后台由系统平滑接管；
 * - API 26–30：在 [onUserLeaveHint] 中按同一策略手动进入；
 * - PiP 期间仍按播放快照刷新窗口标题与动作，从而支持自动连播下一集。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var pictureInPictureSession: PictureInPictureSession

    private lateinit var pictureInPictureController: PictureInPictureController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pictureInPictureController = PictureInPictureController(this)

        // 播放位置每秒变化多次，但 PiP 参数只关心标题/播放状态/比例等字段；
        // 用 shouldRefreshParams 过滤，避免持续调用 setPictureInPictureParams。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                var previous: PictureInPictureSessionState? = null
                pictureInPictureSession.state.collect { state ->
                    val last = previous
                    if (last == null || PictureInPicturePolicy.shouldRefreshParams(last, state)) {
                        pictureInPictureController.apply(state)
                        previous = state
                    }
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            MediaHubTheme {
                MediaHubNavHost()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val state = pictureInPictureSession.state.value
        val shouldEnter = PictureInPicturePolicy.shouldEnterOnUserLeaveHint(
            sdkInt = Build.VERSION.SDK_INT,
            supported = pictureInPictureController.isSupported(),
            autoPictureInPicture = state.autoPictureInPicture,
            isPlaying = state.isPlaying,
            resolveReady = state.resolveReady,
            alreadyInPictureInPicture = isInPictureInPictureMode,
        )
        if (shouldEnter) {
            pictureInPictureController.enter(state)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPictureSession.setInPictureInPicture(isInPictureInPictureMode)
    }
}
