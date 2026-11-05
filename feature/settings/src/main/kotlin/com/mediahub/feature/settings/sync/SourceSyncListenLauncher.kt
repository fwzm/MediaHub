package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 「一次等待 = 一个可取消的 Job + 一个取消把手」这层薄壳（W6-61）。
 *
 * 与 [SourceSyncReceiveLauncher]（W6-58）同一个理由、同一个形状：纯 JVM 单测造不出 ViewModel 的依赖，
 * 而「取消这一次等待必须回『正在监听』、不许伪造失败、监听不关、取消后能立刻再等一次」是用户可见语义，
 * 必须被 JVM 用例钉住。
 *
 * 监听端还有一层与接收端不同的地方：监听本身是长活的，取消**只放弃这一次等待**——
 * 不 close 监听、不时序错乱地把状态写成 Failed。因此 [cancel] 的语义是：
 * - 先让把手关掉「这次若要用的那条连接」（等 accept 时是切片轮询看到取消就返回，读阶段是关 socket）；
 * - 再 cancel() Job，兜住「还没走到阻塞调用」与「替身端口不读 socket」这两种情况；
 * - 取消后控制器的状态是 Listening（不是 Failed、不留码）。
 *
 * 语义与接收端逐条对齐：
 * - 同一时刻只有一条在飞：[launch] 在已在飞时返回 false；
 * - [cancel] 只取消在飞的那条，并把它的 Job 交回去；没有在飞或已结束时是 no-op（返回 null）；
 * - [clear] 只丢引用（页面重置 / 销毁时用），**不取消**：取消必须是用户的显式动作；
 * - 每次 [launch] 都新建一个把手：取消过的把手不会污染下一次等待。
 */
class SourceSyncListenLauncher(
    private val scope: CoroutineScope,
    private val await: suspend (SourceSyncCancellation) -> Boolean,
) {

    private var job: Job? = null

    private var signal: SourceSyncCancellation? = null

    /** 是否有一条等待在飞（界面据此决定取消按钮可不可点）。 */
    val isActive: Boolean
        get() = job?.isActive == true

    /** 起一次等待；已在飞时返回 false（不排队、不重复等）。 */
    fun launch(): Boolean {
        if (isActive) return false
        val cancel = SourceSyncCancellation()
        signal = cancel
        job = scope.launch {
            try {
                await(cancel)
            } finally {
                // 只有自己仍是「当前那条」时才清：否则会把下一次等待的把手抹掉。
                if (signal === cancel) signal = null
            }
        }
        return true
    }

    /**
     * 取消在飞的那条，并把它的 Job 交回去（调用方可以 join 等它真正收尾）。
     *
     * 先关连接再取消协程：关连接能让阻塞的 accept / 读立刻返回（这才是「取消立刻生效」的关键），
     * 取消协程兜住「还没走到阻塞调用」与「替身端口不读 socket」这两种情况。
     */
    fun cancel(): Job? {
        val current = job ?: return null
        if (!current.isActive) return null
        signal?.cancel()
        current.cancel()
        return current
    }

    /** 丢掉引用，不取消（等待自己会随作用域结束而收尾）。 */
    fun clear() {
        job = null
        signal = null
    }
}
