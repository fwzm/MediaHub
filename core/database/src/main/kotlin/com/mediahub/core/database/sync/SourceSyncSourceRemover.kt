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
 * 契约：
 * - 返回 true 表示这条来源（含它的凭据、进度、会话）已经不在本机；
 * - 返回 false 表示本机没有这条来源，或删到一半失败 —— 两者都不算「写过」，调用方会计入 refused；
 * - 抛异常视为失败（调用方折成 refused，不冒给用户）。
 */
interface SourceSyncSourceRemover {
    suspend fun removeSource(serverId: String): Boolean
}
