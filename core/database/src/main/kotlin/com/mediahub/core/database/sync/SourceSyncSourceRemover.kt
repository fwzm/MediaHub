package com.mediahub.core.database.sync

/**
 * 跨端删除的落地能力（W6-79）。
 *
 * 为什么单独一个接口：`SourceSyncStore` 刻意没有删除方法（ADR-S001：墓碑不删本地来源），而「对端声明删除」
 * 是本轮唯一合法删本地来源的时机，且必须**连凭据一起删** —— Windows 侧的注释原话是
 * 「a 'deleted' source whose token is still on disk is not deleted」。
 * 把这条判断塞进存储接口会让八个测试假件都得实现一个多半用不上的方法，也会让「删行」与「删凭据」
 * 在同一个抽象里悄悄分叉；因此这里只声明能力，实现方（生产上是 feature:server 的完整删除链）负责清干净。
 *
 * 契约（W6-88 起返回 [SourceSyncRemoveOutcome]，不再是 Boolean）：
 * - [SourceSyncRemoveOutcome.Removed]：这条来源（含它的凭据、进度、会话）已经不在本机；
 * - [SourceSyncRemoveOutcome.Missing]：本机没有这条来源 —— 不算「写过」，但也不是故障；
 * - [SourceSyncRemoveOutcome.Failed]：删到一半失败，`reason` 是稳定原因码。
 *   生产实现会把删除链的步骤码挂上去（`local-source-remove-failed:<step>`），因为「删了但不知道卡在哪一步」
 *   与「删了」在支持场景里是两件事：凭据没清干净时，用户需要知道该去重试删除还是去来源页手动清。
 *   W6-98 起 [Failed.causeMessage] 再带上那一步自己说的话（由 [SourceSyncFailureCauses] 净化），
 *   与 Windows 把写入器异常消息折进 Refused 行原样显示同口径：「卡在哪一步」与「那一步为什么失败」是两句话。
 * - 抛异常视为失败（调用方折成 refused，不冒给用户）。
 *
 * 为什么不是 Boolean：Boolean 把「本机没有」「链中途失败」「失败在第八步」压成同一个 false，
 * 回执里只剩一句 `local-source-remove-failed`，八处清理里哪一处断掉完全不可见。
 */
interface SourceSyncSourceRemover {
    suspend fun removeSource(serverId: String): SourceSyncRemoveOutcome
}

/** [SourceSyncSourceRemover] 的三种落点。 */
sealed interface SourceSyncRemoveOutcome {
    /** 这条来源连它的凭据、进度、会话都不在本机了。 */
    data object Removed : SourceSyncRemoveOutcome

    /** 本机没有这条来源（空白身份、行已被别处删掉）。 */
    data object Missing : SourceSyncRemoveOutcome

    /**
     * 删到一半失败；[reason] 是稳定原因码，可能带步骤后缀。
     *
     * [causeMessage] 是那一步自己说的话（W6-98）：删除链抛出的异常消息经 [SourceSyncFailureCauses]
     * 净化后的单行文本，没有可用消息时为 null。它只进回执与界面，不参与任何判定。
     */
    data class Failed(
        val reason: String,
        val causeMessage: String? = null,
    ) : SourceSyncRemoveOutcome
}
