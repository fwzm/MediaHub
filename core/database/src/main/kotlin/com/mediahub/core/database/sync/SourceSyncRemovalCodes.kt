package com.mediahub.core.database.sync

/**
 * W6-88：删除链的八步，每一步单独成码。
 *
 * 为什么需要步骤：`SourceSyncSourceRemover` 只回答「删掉了没有」，而删除一条来源要清八处（servers 行、
 * 账号、token、凭据、播放进度、字幕记忆、图标文件、provider 会话）。任何一处失败都整体算失败，但只报
 * `local-source-remove-failed` 等于告诉用户「你的凭据可能还在，但不知道卡在哪」——支持时无从下手。
 * 步骤码挂在原因码尾部（`local-source-remove-failed:<step>`），结果页与日志都能指出确切那一步。
 *
 * 码表放在 core:database：写码的一侧（feature:server 的删除链）与翻译码的一侧（feature:settings 的结果页）
 * 是两个模块，步骤码必须只有一处权威，否则界面会把某一步说成「原因码：servers-row」。
 */
enum class SourceSyncRemovalStep(val code: String) {
    /** `servers` 行（连同它的 endpoints，由 `ServerRepository.deleteServer` 一并清）。 */
    SERVERS("servers-row"),

    /** accounts 表里指向这条来源的账号行。 */
    ACCOUNTS("accounts"),

    /** 凭据柜里的 access/refresh token。 */
    TOKENS("tokens"),

    /** 凭据柜里的密码 / API key 等条目。 */
    CREDENTIALS("credentials"),

    /** playback_progress 表。 */
    PROGRESS("progress"),

    /** subtitle_memory 表。 */
    SUBTITLE_MEMORY("subtitle-memory"),

    /** 自定义图标文件（`server_icons/<serverId>.webp`）。 */
    ICON("icon"),

    /** provider 层缓存的播放会话。 */
    SESSION_STORE("session-store"),
}

/**
 * 跨端删除的稳定原因码（W6-79 起，W6-88 把「删到一半失败」细到步骤）。
 *
 * 前缀与后缀的分工：`local-source-remove-failed` 单独出现表示失败但没有步骤信息（链外异常、或者
 * 实现方不是 [SourceSyncSourceRemover] 的生产实现）；带 `:<step>` 时步骤码一定来自 [SourceSyncRemovalStep]。
 */
object SourceSyncRemovalCodes {
    /** 删除链中途失败。带步骤时写成 `local-source-remove-failed:<step>`。 */
    const val FAILED: String = "local-source-remove-failed"

    /** 对端说删除，但本机本来就没有这条来源。 */
    const val MISSING: String = "local-source-missing"

    /** 本地媒体源（[com.mediahub.model.ServerType.LOCAL]）不参与跨端删除。 */
    const val LOCAL_MEDIA: String = "local-media-source"

    /** 本进程没有接删除能力（没有 [SourceSyncSourceRemover]）。 */
    const val NOT_SUPPORTED: String = "local-delete-not-supported"

    /** 步骤码与失败码之间的分隔符。 */
    const val STEP_SEPARATOR: Char = ':'

    /** `local-source-remove-failed:<step>`：删除链卡在某一步。 */
    fun failedAt(step: SourceSyncRemovalStep): String = FAILED + STEP_SEPARATOR + step.code

    /** 同 [failedAt]，但步骤码是外来字符串（实现方不是本模块时用）；空白步骤退化成 [FAILED]。 */
    fun failedAtStep(code: String): String {
        val trimmed = code.trim()
        return if (trimmed.isEmpty()) FAILED else FAILED + STEP_SEPARATOR + trimmed
    }

    /** 从原因码里取步骤码；不是「带步骤的失败码」时返回 null。 */
    fun stepCodeOf(reason: String): String? {
        val raw = reason.trim()
        val prefix = FAILED + STEP_SEPARATOR
        if (!raw.startsWith(prefix)) return null
        return raw.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
    }
}
