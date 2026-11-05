package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 「一次接收 = 一个可取消的 Job + 一个取消把手」这层薄壳（W6-58）。
 *
 * 与 [SourceSyncSendLauncher]（W6-49）同样的理由：纯 JVM 单测造不出 ViewModel 的依赖，
 * 而「取消必须回 Idle、不许伪造失败、取消后可再来一次」这三条是用户可见语义，必须被 JVM 用例钉住。
 * 与发送那条的**唯一区别**是这里还持有一个 [SourceSyncCancellation]：
 *
 * 分块收帧走的是**阻塞** socket 读，协程 Job 的 cancel 只在挂起点生效——
 * 只 cancel() Job 的话，界面会停在「接收中」直到读超时（缺省 15 秒）才回 Idle。
 * 因此 [cancel] 先让把手关掉连接（被阻塞的读立刻返回、客户端给出本地码 transfer-cancelled），
 * 再 cancel() Job（替身与挂起点兜底）。两条路径最终都写回 Idle，不留失败码。
 *
 * 语义：
 * - 同一时刻只有一条在飞：[launch] 在已在飞时返回 false，不排队、不重复收（与控制器的 inFlight 双保险）；
 * - [cancel] 只取消在飞的那条，并把它交给调用方去等收尾；**取消不是失败**——控制器把状态写回 Idle；
 * - 没有在飞、或那条已经结束时 [cancel] 是 no-op（返回 null，不碰状态、不关任何连接）；
 * - [clear] 只丢引用（页面重置 / 销毁时用），**不取消**：取消必须是用户的显式动作；
 * - 每次 [launch] 都新建一个把手：取消过的把手不会污染下一次接收。
 */
class SourceSyncReceiveLauncher(
    private val scope: CoroutineScope,
    private val receive: suspend (SourceSyncReceiveForm, SourceSyncCancellation) -> Boolean,
) {

    private var job: Job? = null

    private var signal: SourceSyncCancellation? = null

    /** 是否有一条接收在飞（界面据此决定取消按钮可不可点）。 */
    val isActive: Boolean
        get() = job?.isActive == true

    /** 起一条接收；已在飞时返回 false（不排队、不重复收）。 */
    fun launch(form: SourceSyncReceiveForm): Boolean {
        if (isActive) return false
        val cancel = SourceSyncCancellation()
        signal = cancel
        job = scope.launch {
            try {
                receive(form, cancel)
            } finally {
                // 只有自己仍是「当前那条」时才清：否则会把下一次接收的把手抹掉。
                if (signal === cancel) signal = null
            }
        }
        return true
    }

    /**
     * 取消在飞的那条，并把它的 Job 交回去（调用方可以 join 等它真正收尾）。
     *
     * 先关连接再取消协程：关连接能让阻塞的读立刻返回（这才是「取消立刻生效」的关键），
     * 取消协程兜住「还没走到读」与「替身端口不读 socket」这两种情况。
     */
    fun cancel(): Job? {
        val current = job ?: return null
        if (!current.isActive) return null
        signal?.cancel()
        current.cancel()
        return current
    }

    /** 丢掉引用，不取消（接收自己会随作用域结束而收尾）。 */
    fun clear() {
        job = null
        signal = null
    }
}
