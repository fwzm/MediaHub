package com.mediahub.core.database.sync

/**
 * W6-97：接收口回执已收窄成只描述身份的 [SourceSyncReceiveEcho]，写入计数留在 applyRound 的
 * SourceSyncApplyReport 里。这个测试专用网关据此把「旧的一把写全部」接线重新拼出来：
 * 它把写入结果留给自己（[results] / [lastResult]），只把身份回显交给接收层。
 *
 * 目的：让「接收通道装不下写入计数」既有类型证据（回执类型没有计数字段），
 * 又不丢掉「同一封信封走写库网关确实会写两条」的对照证据。
 */
internal fun SourceSyncImportResult.toEcho(): SourceSyncReceiveEcho = SourceSyncReceiveEcho(
    deviceId = deviceId,
    snapshotId = snapshotId,
    envelopeContentHash = envelopeContentHash,
    rejectedReason = rejectedReason,
    rejectedMessage = rejectedMessage,
)

internal class WriteThroughGateway(
    private val service: AndroidSourceSyncService,
) : SourceSyncImportGateway {
    private val recorded = ArrayList<SourceSyncImportResult>()

    /** 每次网关调用留下的写入结果（按调用顺序），断言计数用。 */
    val results: List<SourceSyncImportResult> get() = recorded

    val lastResult: SourceSyncImportResult? get() = recorded.lastOrNull()

    override suspend fun importEnvelope(envelopeJson: String): SourceSyncReceiveEcho {
        val result = service.importEnvelope(envelopeJson)
        recorded.add(result)
        return result.toEcho()
    }
}
