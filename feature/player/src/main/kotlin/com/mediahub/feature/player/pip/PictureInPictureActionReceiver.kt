package com.mediahub.feature.player.pip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * PiP 窗口内快退 / 播放暂停 / 快进的投递终点（条目 123）。
 *
 * 只接受本进程通过 `PendingIntent` 发来的显式广播（manifest 中 `exported=false`），
 * 动作最终交给播放页注册的处理器执行，避免在接收器里持有播放器实例。
 */
@AndroidEntryPoint
class PictureInPictureActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var session: PictureInPictureSession

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return
        val command = PictureInPicturePolicy.commandFor(intent.getStringExtra(EXTRA_COMMAND)) ?: return
        session.dispatch(command)
    }

    companion object {
        const val ACTION_COMMAND = "com.mediahub.feature.player.pip.action.COMMAND"
        const val EXTRA_COMMAND = "com.mediahub.feature.player.pip.extra.COMMAND"
    }
}
