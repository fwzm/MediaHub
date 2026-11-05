package com.mediahub.core.common.sync

import java.security.MessageDigest

/**
 * 来源同步的内容指纹（W6-C）。
 *
 * 为什么需要它：规划器判定"同一修订号下内容是否一致"用的是逐字段比较，这对单条记录够用；
 * 但"这份信封是不是我已经导入过的那一份"需要跨整份信封的稳定摘要，而且不能把
 * deviceId / snapshotId / createdAtEpochMs 这类每次导出都变的字段算进去——否则同一台设备
 * 连续导出两次永远对不上，幂等判定就退化成"只看 revision"。
 *
 * 语义：
 * - [content] 只看"内容"：来源身份、wire 类型、显示名、账号、端点集合（地址先做 D4 规范化，
 *   端点顺序与书写细节不影响结果），**不含 recordRevision**——内容没变而修订号变了，
 *   仍然算同一份内容；
 * - [of] 在内容之上并入 recordRevision，用于报告与排查；
 * - 不包含 secrets：本端不解释、不记录凭据（ADR-S001 D6），指纹里也不留它的痕迹。
 */
object SourceSyncFingerprints {

    private const val FIELD = "\u001F"
    private const val RECORD = "\u001E"

    /** 单条记录的"内容"指纹（不含修订号、不含 secrets）。 */
    fun content(record: SourceRecord): String = sha256(canonicalContent(record))

    /**
     * 整份信封的内容指纹：各记录内容指纹排序后拼接，与信封里的记录顺序无关
     * （顺序是导出端的整理习惯，不是语义）。墓碑不参与——它表达的是"远端删过什么"，
     * 不描述本机来源内容。
     */
    fun content(envelope: SourceSyncEnvelope): String =
        sha256(envelope.sources.map { canonicalContent(it) }.sorted().joinToString(RECORD))

    /** 内容 + 修订号，便于日志与测试定位"同修订号不同内容"的情形。 */
    fun of(record: SourceRecord): String =
        sha256(canonicalContent(record) + FIELD + "revision=" + record.recordRevision)

    private fun canonicalContent(record: SourceRecord): String {
        val builder = StringBuilder()
        builder.append("id=").append(record.syncSourceId.trim()).append(FIELD)
        builder.append("wire=").append(record.sourceType.wire).append(FIELD)
        builder.append("name=").append(record.displayName).append(FIELD)
        builder.append("scope=").append(record.accountScope?.trim().orEmpty()).append(FIELD)
        // 端点按"规范化地址 + 标志"排序后写入：发送端的书写细节（尾斜杠、默认端口、大小写）
        // 与数组顺序都不改变内容同一性，语义变化（主端点换地址、端点被禁用）才会改变指纹。
        val endpoints = record.endpoints
            .map { EndpointPart(url = normalizedUrl(it.url), isPrimary = it.isPrimary, enabled = it.enabled) }
            .sortedWith(compareBy({ it.url }, { it.isPrimary }, { it.enabled }))
        for (endpoint in endpoints) {
            builder.append("ep=").append(endpoint.url)
                .append(",primary=").append(endpoint.isPrimary)
                .append(",enabled=").append(endpoint.enabled)
                .append(FIELD)
        }
        return builder.toString()
    }

    private fun normalizedUrl(url: String): String = SyncUrlNormalizer.normalize(url) ?: url.trim()

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            builder.append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0F])
        }
        return builder.toString()
    }

    private const val HEX = "0123456789abcdef"

    private data class EndpointPart(val url: String, val isPrimary: Boolean, val enabled: Boolean)
}
