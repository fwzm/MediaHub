package com.mediahub.core.common.sync

import kotlinx.serialization.json.JsonNull

/**
 * 「这条记录是否携带凭据块」的唯一判据（W6-74）。
 *
 * 三处调用方必须给出同一个结论，所以判据只写一遍：
 * - [com.mediahub.core.common.archive.ArchiveWriter]：带凭据的信封不准写进归档文件；
 * - Android 导入路径（core:database 的 AndroidSourceSyncService）：带凭据的记录不落库、转人工确认；
 * - Windows 侧 SourceSyncImportRound / SyncRoundMerger 各自有同义实现（原话是 ValueKind != Null）。
 *
 * 判据必须排除 JSON null：Windows 的原话是 ValueKind != Null，即 `secrets: null` 不算携带。
 * Kotlin 里 [JsonNull] 是一个对象而不是 Kotlin null，只写 `!= null` 会把显式 null 误判成凭据，
 * 两端遂对同一份信封给出不同结论（Windows 允许写、Android 拒绝）——这是跨端契约上的真缺陷，
 * 由 ArchiveRoundTripTest 的 a_json_null_secrets_block_is_not_credentials_on_either_side 钉住。
 *
 * 本对象只回答"有没有"，不解析、不校验、不搬运凭据内容：凭据必须由用户在本机重新输入
 * （经 SecretStorage 重新封装），远端密文不可直接落库，也不该出现在日志里。
 */
object SourceSyncSecrets {

    /** 这条记录带的是**真凭据**（secrets 存在且不是 JSON null）。 */
    fun carriesBlock(record: SourceRecord?): Boolean {
        val secrets = record?.secrets ?: return false
        return secrets !is JsonNull
    }

    /** 整份信封里任一来源带真凭据。 */
    fun carriesBlock(envelope: SourceSyncEnvelope?): Boolean =
        envelope?.sources?.any { carriesBlock(it) } == true
}
