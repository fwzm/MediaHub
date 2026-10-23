package com.mediahub.core.common.sync

/**
 * 稳定的 reason 码（与 Windows SourceSyncReasons 逐字相同）。
 *
 * 这些字符串是两端共同的词汇表：UI 用它选文案，测试用它断言，排障时两边对得上。
 * 它们是客户端内部码，不进线格式，改字面量不会破坏协议，但会让两端诊断口径分叉，故视为常量。
 *
 * NOT_SUPPORTED_ON_WINDOWS 的说明：字面量保留了合同里最初的名字（Windows 是第一个实现），
 * Android 侧复用它表示"协议认识、但本端没有对应类型"，而不是另造一个 not-supported-on-android。
 * 理由是同一种情形只应有一个码；今天 Android 覆盖了全部 12 个 wire 值，该分支实际不可达，
 * 保留它是为了与 Windows 规则一一对应，并在未来合同新增词汇时立刻有正确的失败姿势。
 */
object SourceSyncReasons {
    const val NEW_SOURCE: String = "new-source"
    const val HIGHER_REVISION: String = "higher-revision"
    const val UP_TO_DATE: String = "up-to-date"
    const val SAME_REVISION_DIFFERENT_CONTENT: String = "same-revision-different-content"
    const val STALE_REVISION: String = "stale-revision"
    const val TOMBSTONE: String = "tombstone"
    const val NO_LOCAL_SOURCE: String = "no-local-source"
    const val UNKNOWN_SOURCE_TYPE: String = "unknown-source-type"
    const val NOT_SUPPORTED_ON_WINDOWS: String = "not-supported-on-windows"
    const val LEGACY_IDENTITY_MATCH: String = "legacy-identity-match"
    const val AMBIGUOUS_LEGACY_IDENTITY: String = "ambiguous-legacy-identity"
    const val DUPLICATE_LOCAL_SYNC_ID: String = "duplicate-local-sync-id"
    const val NO_USABLE_ENDPOINT: String = "no-usable-endpoint"

    /** 全部码，按 Windows 类的声明顺序；测试用它钉住"没有多也没有少"。 */
    val ALL: List<String> = listOf(
        NEW_SOURCE,
        HIGHER_REVISION,
        UP_TO_DATE,
        SAME_REVISION_DIFFERENT_CONTENT,
        STALE_REVISION,
        TOMBSTONE,
        NO_LOCAL_SOURCE,
        UNKNOWN_SOURCE_TYPE,
        NOT_SUPPORTED_ON_WINDOWS,
        LEGACY_IDENTITY_MATCH,
        AMBIGUOUS_LEGACY_IDENTITY,
        DUPLICATE_LOCAL_SYNC_ID,
        NO_USABLE_ENDPOINT,
    )
}
