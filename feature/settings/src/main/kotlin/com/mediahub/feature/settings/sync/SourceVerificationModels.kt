package com.mediahub.feature.settings.sync

import com.mediahub.model.ServerType

/**
 * 单个来源的验证三态（W6-H）。
 *
 * 为什么必须三态而不是一个布尔值：W6-F 导入落库后来源**没有**任何凭据，
 * 界面若只显示"可用/不可用"，用户会以为导入即登录（ADR-S001 D7 明令禁止这种暗示）。
 * 三态把"还没补录"与"补录了但服务器拒绝"分开，前者不发起任何网络请求。
 *
 * - [NEEDS_CREDENTIALS]：本机没有该来源的密码（导入后的默认状态）。不发请求。
 * - [VERIFIED]：用本机凭据真实调用了 provider 的认证接口并且成功。
 * - [FAILED]：调用过但失败（或凭据不可读/不可存），原因码见 [SourceVerificationReasons]。
 */
enum class SourceVerificationState {
    NEEDS_CREDENTIALS,
    VERIFIED,
    FAILED,
}

/**
 * 验证失败/成功的稳定原因码（封闭集合，测试逐个钉住）。
 *
 * 这些码会进结果页文案与日志，但**永远不含**密码：码本身是常量，不拼接任何用户输入。
 */
object SourceVerificationReasons {
    /** 本机没有该来源的密码：提示补录，不调用认证接口。 */
    const val NO_CREDENTIALS: String = "no-credentials"

    /** 来源没有账号（username 为空）：无法做用户名密码登录，也不该发请求。 */
    const val MISSING_ACCOUNT: String = "missing-account"

    /** 服务器拒绝了这组凭据（401 / AuthFailed）。 */
    const val AUTH_FAILED: String = "auth-failed"

    /** 该来源类型没有认证能力（或 Provider 未实现），不是凭据错。 */
    const val AUTH_NOT_SUPPORTED: String = "auth-not-supported"

    /** 认证调用抛了异常（网络/协议）：结论是"这次没验成"，不是"密码错"。 */
    const val AUTH_UNAVAILABLE: String = "auth-unavailable"

    /** 本机安全存储读不出来（Keystore 不可用等）。 */
    const val CREDENTIALS_UNAVAILABLE: String = "credentials-unavailable"

    /** 认证通过但凭据没能写进安全存储：本轮补录没完成，必须如实报告。 */
    const val CREDENTIALS_SAVE_FAILED: String = "credentials-save-failed"

    /** 认证通过。 */
    const val VERIFIED: String = "verified"

    /** 封闭集合：测试与文档都以此为准，新增码必须同时改这里。 */
    val ALL: Set<String> = setOf(
        NO_CREDENTIALS,
        MISSING_ACCOUNT,
        AUTH_FAILED,
        AUTH_NOT_SUPPORTED,
        AUTH_UNAVAILABLE,
        CREDENTIALS_UNAVAILABLE,
        CREDENTIALS_SAVE_FAILED,
        VERIFIED,
    )
}

/** 界面固定文案（集中管理，测试按常量断言，避免文案漂移让证据失效）。 */
object SourceVerificationCopy {
    /** 脱敏占位符：任何进入文案/日志的用户输入（密码、账号）都替换成它。 */
    const val MASK: String = "***"

    const val VERIFY_ACTION: String = "补录并验证"
    const val DIALOG_TITLE: String = "补录凭据并验证"
    const val DIALOG_HINT: String =
        "密码只写入本机安全存储（Android Keystore），不会进入数据库、日志或同步信封。"
    const val INPUT_LABEL: String = "密码"
    const val CONFIRM: String = "保存并验证"
    const val CANCEL: String = "取消"

    const val NEEDS_CREDENTIALS_TEXT: String = "需补录凭据"
    const val VERIFIED_TEXT: String = "已验证"
    const val FAILED_PREFIX: String = "验证失败："

    const val NO_CREDENTIALS_MESSAGE: String = "尚未补录密码"
    const val MISSING_ACCOUNT_MESSAGE: String = "来源缺少账号，无法登录"
    const val AUTH_FAILED_MESSAGE: String = "账号或密码不被服务器接受"
    const val NOT_SUPPORTED_MESSAGE: String = "该来源暂不支持密码登录"
    const val UNAVAILABLE_MESSAGE: String = "认证服务不可用，请稍后重试"
    const val CREDENTIALS_UNAVAILABLE_MESSAGE: String = "本机安全存储不可用"
    const val SAVE_FAILED_MESSAGE: String = "认证通过但凭据未能保存，请重试"
    const val VERIFIED_MESSAGE: String = "认证通过"

    /** 原因码 -> 固定中文说明；未知码兜底成"验证失败（码）"，不拼接任何用户输入。 */
    fun reasonText(reason: String): String = when (reason) {
        SourceVerificationReasons.NO_CREDENTIALS -> NO_CREDENTIALS_MESSAGE
        SourceVerificationReasons.MISSING_ACCOUNT -> MISSING_ACCOUNT_MESSAGE
        SourceVerificationReasons.AUTH_FAILED -> AUTH_FAILED_MESSAGE
        SourceVerificationReasons.AUTH_NOT_SUPPORTED -> NOT_SUPPORTED_MESSAGE
        SourceVerificationReasons.AUTH_UNAVAILABLE -> UNAVAILABLE_MESSAGE
        SourceVerificationReasons.CREDENTIALS_UNAVAILABLE -> CREDENTIALS_UNAVAILABLE_MESSAGE
        SourceVerificationReasons.CREDENTIALS_SAVE_FAILED -> SAVE_FAILED_MESSAGE
        SourceVerificationReasons.VERIFIED -> VERIFIED_MESSAGE
        else -> "验证失败（" + reason + "）"
    }

    /** 状态行文案：三态必须一眼可分（UI 测试按这三串断言）。 */
    fun stateText(state: SourceVerificationState, reason: String): String = when (state) {
        SourceVerificationState.NEEDS_CREDENTIALS -> NEEDS_CREDENTIALS_TEXT
        SourceVerificationState.VERIFIED -> VERIFIED_TEXT
        SourceVerificationState.FAILED -> FAILED_PREFIX + reasonText(reason)
    }
}

/**
 * 参与验证的一条本机来源快照（只读）。
 *
 * 刻意不传 [com.mediahub.model.MediaServer]：验证不该拿到可写模型，
 * 从类型上排除"验证顺手改了来源名称/地址/syncSourceId"的可能。
 */
data class SourceVerificationSource(
    val serverId: String,
    val title: String,
    /** 类型允许为空：未知/暂不支持的来源没有可用的 [ServerType]，验证器据此报 auth-not-supported。 */
    val serverType: ServerType? = null,
    /** 账号作用域（username）；为空时由生产校验器回落到本机 servers 表里的账号。 */
    val accountScope: String? = null,
    /** 同步身份（只用于展示与排查，验证流程绝不修改它）。 */
    val syncSourceId: String? = null,
)

/** 校验端口的一次判定（生产实现由 provider 层的 MediaAuthProvider.authenticate 得到）。 */
data class SourceAuthVerdict(
    val verified: Boolean,
    val reason: String = if (verified) SourceVerificationReasons.VERIFIED else SourceVerificationReasons.AUTH_FAILED,
    /** 可选的补充说明；进文案前会被脱敏（密码/账号替换成 ***）。 */
    val message: String? = null,
)

/** 一条来源的验证结果。 */
data class SourceVerificationOutcome(
    val serverId: String,
    val state: SourceVerificationState,
    val reason: String,
    /** 给用户看的一句话（已脱敏）。 */
    val message: String,
    /** 判定发生的时间；未发起校验（NEEDS_CREDENTIALS）时为 null。 */
    val checkedAtEpochMs: Long? = null,
) {
    val needsCredentials: Boolean get() = state == SourceVerificationState.NEEDS_CREDENTIALS

    val isVerified: Boolean get() = state == SourceVerificationState.VERIFIED

    val isFailed: Boolean get() = state == SourceVerificationState.FAILED

    /** 状态行文案（结果页每源一行，testTag sync.source.verify.state）。 */
    val stateText: String get() = SourceVerificationCopy.stateText(state, reason)
}

/**
 * 结果页的验证视图状态（纯数据，可被 JVM 单测直接构造）。
 *
 * [outcomes] 以 serverId 为键；没有结果 = 还没验证（显示 [SourceVerificationState.NEEDS_CREDENTIALS]）。
 * [dialogServerId] 非空表示"补录并验证"对话框正开着哪一条。
 */
data class SourceVerificationViewState(
    val sources: List<SourceVerificationSource> = emptyList(),
    val outcomes: Map<String, SourceVerificationOutcome> = emptyMap(),
    val dialogServerId: String? = null,
    val busyServerId: String? = null,
) {
    val hasSources: Boolean get() = sources.isNotEmpty()

    val dialogSource: SourceVerificationSource?
        get() = sources.firstOrNull { it.serverId == dialogServerId }

    val isDialogOpen: Boolean get() = dialogSource != null

    fun outcomeFor(serverId: String): SourceVerificationOutcome? = outcomes[serverId]

    fun stateOf(serverId: String): SourceVerificationState =
        outcomes[serverId]?.state ?: SourceVerificationState.NEEDS_CREDENTIALS

    /** 每源状态行文案；未验证的来源显示"需补录凭据"。 */
    fun lineFor(serverId: String): String =
        outcomes[serverId]?.stateText
            ?: SourceVerificationCopy.stateText(
                SourceVerificationState.NEEDS_CREDENTIALS,
                SourceVerificationReasons.NO_CREDENTIALS,
            )

    fun isBusy(serverId: String): Boolean = busyServerId == serverId

    /** 换一批来源：保留仍然存在的来源的旧结论，避免一次刷新把已验证状态清空。 */
    fun withSources(next: List<SourceVerificationSource>): SourceVerificationViewState {
        val ids = next.map { it.serverId }.toSet()
        return copy(
            sources = next,
            outcomes = outcomes.filterKeys { ids.contains(it) },
            dialogServerId = dialogServerId?.takeIf { ids.contains(it) },
            busyServerId = busyServerId?.takeIf { ids.contains(it) },
        )
    }

    fun withOutcome(outcome: SourceVerificationOutcome): SourceVerificationViewState =
        copy(outcomes = outcomes + (outcome.serverId to outcome))

    fun withOutcomes(next: List<SourceVerificationOutcome>): SourceVerificationViewState =
        copy(outcomes = next.associateBy { it.serverId })

    fun withDialog(serverId: String?): SourceVerificationViewState = copy(dialogServerId = serverId)

    fun withBusy(serverId: String?): SourceVerificationViewState = copy(busyServerId = serverId)
}

