package com.mediahub.feature.settings.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 「一次发送 = 一个可取消的 Job」这层薄壳（W6-49）。
 *
 * 为什么不把这几个字段直接摊在 [SourceSyncImportViewModel] 里：那个 ViewModel 的构造依赖 Room 仓库、
 * Keystore 凭据库与 provider 注册表，纯 JVM 单测造不出来；而「取消必须回 Idle、不许伪造失败、取消后可再发」
 * 这三条是用户可见语义，必须被 JVM 用例真正钉住。把与状态机无关的记账（持有 Job / 取消 Job / 丢引用）
 * 收进这个纯 Kotlin 类，ViewModel 只剩两行委托。**状态机仍在 [SourceSyncSendController]，这里不复制任何状态**。
 *
 * 语义：
 * - 同一时刻只有一条在飞：[launch] 在已在飞时返回 false，不排队、不重复发（与控制器的 inFlight 双保险）；
 * - [cancel] 只取消在飞的那条，并把它交给调用方去等收尾；**取消不是失败**——控制器把状态写回 Idle，
 *   不产生任何稳定码，也不会留下 Sending；
 * - 没有在飞、或那条已经结束时 [cancel] 是 no-op（返回 null，不碰状态）；
 * - [clear] 只丢引用（页面重置 / 销毁时用），**不取消**：取消必须是用户的显式动作。
 */
class SourceSyncSendLauncher(
    private val scope: CoroutineScope,
    private val send: suspend (SourceSyncSendForm) -> Boolean,
) {

    private var job: Job? = null

    /** 是否有一条发送在飞（界面据此决定取消按钮可不可点）。 */
    val isActive: Boolean
        get() = job?.isActive == true

    /** 起一条发送；已在飞时返回 false（不排队、不重复发）。 */
    fun launch(form: SourceSyncSendForm): Boolean {
        if (isActive) return false
        job = scope.launch { send(form) }
        return true
    }

    /**
     * 取消在飞的那条，并把它的 Job 交回去（调用方可以 join 等它真正收尾）。
     *
     * 没有在飞时返回 null —— 与 [SourceSyncImportViewModel.cancelSend] 对应的「没在发就什么都不做」。
     */
    fun cancel(): Job? {
        val current = job ?: return null
        if (!current.isActive) return null
        current.cancel()
        return current
    }

    /** 丢掉引用，不取消（发送自己会随作用域结束而收尾）。 */
    fun clear() {
        job = null
    }
}
