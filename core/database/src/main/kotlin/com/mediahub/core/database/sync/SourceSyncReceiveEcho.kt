package com.mediahub.core.database.sync

/**
 * W6-97：接收口（[SourceSyncImportGateway]）能拿到的**全部**信息。
 *
 * 接收发生在用户预览与逐条允许之前，所以这条通道能回答的问题只有两个：
 * 「这份信封是谁发的、内容指纹是什么」与「解码为什么没通过」。
 * 写入的计数（appliedCount / hasWrites / requiresApprovalCount 等）属于写库路径
 * （[AndroidSourceSyncService.applyRound] 的 [SourceSyncApplyReport]），**不进接收通道**：
 * 接收口收完一封信，界面不可能从它那里读到「写了几条」——因为一个字节都没写。
 *
 * 类型收窄成 W6-97 之前，网关回执直接复用 [SourceSyncImportResult]（写库回执），
 * 于是「接收 = 只读」在类型上看不出来：任何把写库服务接进接收口的人都能顺便把写入数
 * 报成接收结果。现在这层保证是结构性的：回执里没有可填计数的字段。
 *
 * 字段与 [SourceSyncImportResult] 的同名项逐字一致（deviceId / snapshotId /
 * envelopeContentHash / rejectedReason / rejectedMessage），界面与测试的读取点零改动。
 */
data class SourceSyncReceiveEcho(
    /** 信封自报的设备标识（回显，便于排查是哪台机器发的）。 */
    val deviceId: String? = null,
    val snapshotId: String? = null,
    /** 整份信封的内容摘要（不含 deviceId / snapshotId / 时间戳），界面据此判断「这份信封收过没有」。 */
    val envelopeContentHash: String? = null,
    /** 非空表示整份信封没被读入：解析或版本校验失败时来自 codec，写入路径的整封拒绝也走这里。 */
    val rejectedReason: String? = null,
    val rejectedMessage: String? = null,
) {
    val isRejected: Boolean get() = !rejectedReason.isNullOrBlank()
}
