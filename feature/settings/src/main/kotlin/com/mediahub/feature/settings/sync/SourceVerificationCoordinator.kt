package com.mediahub.feature.settings.sync

import kotlin.coroutines.cancellation.CancellationException

/**
 * 凭据端口：只暴露"有没有 / 读 / 写"，没有删除、也没有批量导出。
 *
 * 生产实现是 core/security 的 CredentialVault（Android Keystore）；
 * 之所以抽成端口：验证过程必须能被 JVM 单测完全控制，且不能让验证逻辑拿到可写数据库。
 */
interface SourceCredentialPort {
    suspend fun hasSecret(serverId: String): Boolean

    suspend fun readSecret(serverId: String): String?

    suspend fun saveSecret(serverId: String, secret: String)
}

/**
 * 校验端口：由 provider 层实现（provider:emby / provider:jellyfin 的 MediaAuthProvider.authenticate）。
 *
 * 返回 [SourceAuthVerdict] 而不是布尔值：失败原因要能区分"服务器拒绝"与"这个类型不支持登录"，
 * 否则 UI 只能显示一句无信息量的"验证失败"。
 */
fun interface SourceAuthenticator {
    suspend fun authenticate(
        source: SourceVerificationSource,
        username: String?,
        secret: String,
    ): SourceAuthVerdict
}

/** 日志端口（line 里绝不允许出现密码/令牌；协调器只写来源 id、状态与原因码）。 */
fun interface SourceVerificationLog {
    fun record(line: String)
}

/**
 * 逐源验证协调器（W6-H 的纯逻辑核心）。
 *
 * 不变量（全部由 SourceVerificationCoordinatorTest 的 JVM 单测钉住）：
 * 1. 本机没有凭据时不发起任何认证请求，结论是 NEEDS_CREDENTIALS（导入后的默认状态）。
 * 2. 只有认证通过才把密码写进安全存储；失败/不支持/异常一律不落盘。
 * 3. 验证从不删除来源、从不修改 syncSourceId：本类没有 store 依赖，传入的是只读
 *    [SourceVerificationSource] 快照，因此"改身份"在类型层面就不可能。
 * 4. 密码不进日志、不进文案：日志只记 serverId/state/reason；校验器返回的 message
 *    会先脱敏（密码与账号替换成 ***）再落到 [SourceVerificationOutcome.message]。
 * 5. 结论码来自封闭集合 [SourceVerificationReasons]；校验器给了不认识的码就归一到 auth-failed。
 *
 * 上层（ViewModel）负责"当前开着哪个对话框 / 哪一条正在忙"，本类只做判定，便于单测直接调用。
 */
class SourceVerificationCoordinator(
    private val credentials: SourceCredentialPort,
    private val authenticator: SourceAuthenticator,
    private val log: SourceVerificationLog = SourceVerificationLog { },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 批量判定：一个来源一条结论，顺序与入参一致。 */
    suspend fun inspect(sources: List<SourceVerificationSource>): List<SourceVerificationOutcome> {
        val outcomes = ArrayList<SourceVerificationOutcome>(sources.size)
        for (source in sources) {
            outcomes.add(inspect(source))
        }
        return outcomes
    }

    /** 只读探测：有已存凭据就真的去认证一次（不落盘），没有就报 NEEDS_CREDENTIALS 且零请求。 */
    suspend fun inspect(source: SourceVerificationSource): SourceVerificationOutcome {
        val stored = try {
            credentials.readSecret(source.serverId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return recordFailure(
                source,
                SourceVerificationReasons.CREDENTIALS_UNAVAILABLE,
                SourceVerificationCopy.CREDENTIALS_UNAVAILABLE_MESSAGE,
            )
        }
        if (stored.isNullOrBlank()) {
            return recordOutcome(
                SourceVerificationOutcome(
                    serverId = source.serverId,
                    state = SourceVerificationState.NEEDS_CREDENTIALS,
                    reason = SourceVerificationReasons.NO_CREDENTIALS,
                    message = SourceVerificationCopy.NO_CREDENTIALS_MESSAGE,
                    checkedAtEpochMs = null,
                ),
            )
        }
        return authenticate(source = source, secret = stored, persistOnSuccess = false)
    }

    /**
     * 用户补录并验证：
     * - 没输入密码（空白）时退化成 [inspect]（只检查已存的凭据，不写任何东西）；
     * - 有输入时不先落盘，先拿去认证；只有认证通过才写入安全存储。
     */
    suspend fun verify(source: SourceVerificationSource, secret: String?): SourceVerificationOutcome {
        val typed = secret?.takeIf { it.isNotBlank() } ?: return inspect(source)
        return authenticate(source = source, secret = typed, persistOnSuccess = true)
    }

    private suspend fun authenticate(
        source: SourceVerificationSource,
        secret: String,
        persistOnSuccess: Boolean,
    ): SourceVerificationOutcome {
        val account = source.accountScope?.trim()?.takeIf { it.isNotEmpty() }
        val verdict = try {
            authenticator.authenticate(source, account, secret)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return recordFailure(
                source,
                SourceVerificationReasons.AUTH_UNAVAILABLE,
                SourceVerificationCopy.UNAVAILABLE_MESSAGE,
            )
        }
        if (!verdict.verified) {
            val reason = verdict.reason.takeIf { SourceVerificationReasons.ALL.contains(it) }
                ?: SourceVerificationReasons.AUTH_FAILED
            val message = sanitize(verdict.message, secret, account)
                ?: SourceVerificationCopy.reasonText(reason)
            return recordOutcome(
                SourceVerificationOutcome(
                    serverId = source.serverId,
                    state = SourceVerificationState.FAILED,
                    reason = reason,
                    message = message,
                    checkedAtEpochMs = clock(),
                ),
            )
        }
        if (persistOnSuccess && !persist(source, secret)) {
            return recordFailure(
                source,
                SourceVerificationReasons.CREDENTIALS_SAVE_FAILED,
                SourceVerificationCopy.SAVE_FAILED_MESSAGE,
            )
        }
        return recordOutcome(
            SourceVerificationOutcome(
                serverId = source.serverId,
                state = SourceVerificationState.VERIFIED,
                reason = SourceVerificationReasons.VERIFIED,
                message = SourceVerificationCopy.VERIFIED_MESSAGE,
                checkedAtEpochMs = clock(),
            ),
        )
    }

    /** 写入凭据（相同值不重复写，保证"重复验证"幂等）。返回是否写入成功。 */
    private suspend fun persist(source: SourceVerificationSource, secret: String): Boolean =
        try {
            val existing = credentials.readSecret(source.serverId)
            if (existing != secret) {
                credentials.saveSecret(source.serverId, secret)
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            false
        }

    private fun recordFailure(
        source: SourceVerificationSource,
        reason: String,
        message: String,
    ): SourceVerificationOutcome = recordOutcome(
        SourceVerificationOutcome(
            serverId = source.serverId,
            state = SourceVerificationState.FAILED,
            reason = reason,
            message = message,
            checkedAtEpochMs = clock(),
        ),
    )

    /** 记一行日志（只有 id/状态/原因码与时间，绝无密码与账号）后原样返回结论。 */
    private fun recordOutcome(outcome: SourceVerificationOutcome): SourceVerificationOutcome {
        log.record(
            "source-verify serverId=" + outcome.serverId +
                " state=" + outcome.state.name +
                " reason=" + outcome.reason +
                " checkedAt=" + (outcome.checkedAtEpochMs ?: 0L),
        )
        return outcome
    }

    /** 把校验器给的自由文本里的密码与账号替换成 ***；空文本返回 null（改走固定文案）。 */
    private fun sanitize(message: String?, secret: String, account: String?): String? {
        if (message.isNullOrBlank()) return null
        var text = message
        if (secret.isNotEmpty()) {
            text = text.replace(secret, SourceVerificationCopy.MASK)
        }
        if (!account.isNullOrEmpty()) {
            text = text.replace(account, SourceVerificationCopy.MASK)
        }
        return text
    }
}

