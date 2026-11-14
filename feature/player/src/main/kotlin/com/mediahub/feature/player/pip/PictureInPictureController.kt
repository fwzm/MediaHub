package com.mediahub.feature.player.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import com.mediahub.feature.player.R

/**
 * PiP 窗口参数的唯一构造点（条目 30 / 31 / 123 / 137）。
 *
 * 真机是否真的出现窗口由系统决定，这里负责把窗口比例、动作按钮与自动进入开关
 * 正确交给系统；比例取视频自身比例，避免字幕在切到 PiP 后相对位置漂移。
 */
class PictureInPictureController(private val activity: Activity) {

    fun isSupported(): Boolean = PictureInPicturePolicy.isSupported(
        sdkInt = Build.VERSION.SDK_INT,
        hasSystemFeature = activity.packageManager
            .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
    )

    fun buildParams(state: PictureInPictureSessionState): PictureInPictureParams? {
        if (!isSupported()) return null
        val aspect = PictureInPicturePolicy.aspectRatio(state.videoWidth, state.videoHeight)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(aspect.width, aspect.height))
        // 连播切集后窗口标题跟随新集（条目 113）；setTitle 仅 API 33+ 提供。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            state.title?.takeIf { it.isNotBlank() }?.let { builder.setTitle(it) }
        }

        val actions = if (state.playerActive && state.resolveReady) {
            remoteActions(state.isPlaying)
        } else {
            emptyList()
        }
        if (actions.isNotEmpty()) builder.setActions(actions)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(
                PictureInPicturePolicy.shouldAutoEnter(
                    supported = true,
                    autoPictureInPicture = state.autoPictureInPicture,
                    isPlaying = state.isPlaying,
                    resolveReady = state.playerActive && state.resolveReady,
                    alreadyInPictureInPicture = state.inPictureInPicture,
                ),
            )
        }
        return builder.build()
    }

    /** 后台自动进入所需的参数更新；Activity 不可见或设备不支持时静默跳过。 */
    fun apply(state: PictureInPictureSessionState): Boolean {
        val params = buildParams(state) ?: return false
        return runCatching { activity.setPictureInPictureParams(params) }.isSuccess
    }

    /** 用户主动点「画中画」入口。 */
    fun enter(state: PictureInPictureSessionState): Boolean {
        val params = buildParams(state) ?: return false
        return runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
    }

    private fun remoteActions(isPlaying: Boolean): List<RemoteAction> = listOf(
        remoteAction(
            command = PipCommand.REWIND,
            iconRes = android.R.drawable.ic_media_rew,
            titleRes = R.string.pip_action_rewind,
        ),
        remoteAction(
            command = PipCommand.TOGGLE_PLAY_PAUSE,
            iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            titleRes = if (isPlaying) R.string.pip_action_pause else R.string.pip_action_play,
        ),
        remoteAction(
            command = PipCommand.FORWARD,
            iconRes = android.R.drawable.ic_media_ff,
            titleRes = R.string.pip_action_forward,
        ),
    )

    private fun remoteAction(command: PipCommand, iconRes: Int, titleRes: Int): RemoteAction {
        val title = activity.getString(titleRes)
        val intent = Intent(activity, PictureInPictureActionReceiver::class.java)
            .setAction(PictureInPictureActionReceiver.ACTION_COMMAND)
            .putExtra(PictureInPictureActionReceiver.EXTRA_COMMAND, command.name)
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            REQUEST_CODE_BASE + command.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(activity, iconRes), title, title, pendingIntent)
    }

    private companion object {
        const val REQUEST_CODE_BASE = 4100
    }
}
