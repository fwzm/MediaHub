package com.mediahub.core.network

import okhttp3.HttpUrl

/**
 * MediaHub 的默认 User-Agent（M0-A / ADR-045）。
 *
 * 设计原则：
 * - **可识别**：服务器管理员能一眼看出请求来自 MediaHub，而不是底层库默认标识
 *   （Media3/OkHttp 的 okhttp/x.y、mpv 的 Lavf/xx），后者常被白名单服务器判为异常客户端。
 * - **不伪装**：本策略**不会**冒充其它播放器；自定义 UA 必须由用户显式配置（见 [UserAgentPolicy]）。
 * - **单一来源**：所有出网路径（API / 图片 / 媒体流 / 字幕 / 进度上报）共用同一策略，
 *   避免"API 一个 UA、媒体流另一个 UA"被服务器识别为会话不一致。
 */
object MediaHubUserAgent {

    /** 产品标识（固定前缀，便于服务器侧识别）。 */
    const val PRODUCT: String = "MediaHub"

    /** 版本未知时的默认 UA。 */
    const val DEFAULT: String = "MediaHub/1.0 (Android)"

    /**
     * 由应用版本号构造 UA。版本号来自应用自身的版本信息（不是服务器返回数据）。
     * 传入 null/空白时退化为 [DEFAULT]。
     */
    fun ofVersion(versionName: String?, androidRelease: String? = null): String {
        val v = versionName?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0"
        val os = androidRelease?.trim()?.takeIf { it.isNotEmpty() }
        return if (os == null) "MediaHub/$v (Android)" else "MediaHub/$v (Android $os)"
    }
}

/** UA 校验结果。无效时 [reason] 给出用户可读原因，[value] 为 null。 */
data class UserAgentValidation(
    val value: String?,
    val reason: String? = null,
) {
    val valid: Boolean get() = reason == null
}

/**
 * 自定义 UA 校验（安全边界）。
 *
 * 规则（拒绝而非静默改写，避免用户以为配置生效而实际被改过）：
 * - 空白 → 视为"清除自定义，回到默认 UA"（合法，value = null）；
 * - 含 CR/LF 或任何 C0/C1 控制字符 → 拒绝（HTTP 头注入）；
 * - 含非 ASCII 字符 → 拒绝（UA 按 RFC 7231 为可见 ASCII；非 ASCII 在不同服务器上行为不一致）；
 * - 长度 > [MAX_LENGTH] → 拒绝（防止把请求头撑爆/触发服务器侧异常）。
 */
object UserAgentValidator {

    const val MAX_LENGTH: Int = 200

    fun validate(raw: String?): UserAgentValidation {
        val value = raw ?: return UserAgentValidation(value = null, reason = null)
        // 先按**原样**检查控制字符：绝不能依赖 trim 把 CR/LF 静默消掉——
        // 那会把"拒绝注入"变成"恰好无害"，一旦后续代码改成不 trim 就立刻可被利用。
        for (ch in value) {
            val code = ch.code
            val control = code < 0x20 || code == 0x7F || (code in 0x80..0x9F)
            if (control) return UserAgentValidation(null, "User-Agent 含控制字符（禁止 CR/LF 注入）")
            if (code > 0x7E) return UserAgentValidation(null, "User-Agent 只能包含可见 ASCII 字符")
        }
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return UserAgentValidation(value = null, reason = null)
        if (trimmed.length > MAX_LENGTH) {
            return UserAgentValidation(null, "User-Agent 过长（上限 $MAX_LENGTH 字符）")
        }
        return UserAgentValidation(value = trimmed)
    }
}

/**
 * 服务器作用域的 User-Agent 解析器（M0-A / ADR-045）。
 *
 * - 按 **serverId** 作用域：不同服务器可以有不同 UA，互不串用；
 * - 覆盖来源通过构造参数注入（函数式快照），因此**不存在全局可变单例**；
 * - 未被覆盖或 serverId 为空/未知 → 返回默认 UA，绝不猜测。
 *
 * @param overrides 返回 serverId → 自定义 UA 的快照；实现必须是线程安全的读取。
 * @param defaultUserAgent 未配置时使用的默认 UA。
 */
class UserAgentPolicy(
    private val overrides: () -> Map<String, String> = { emptyMap() },
    private val defaultUserAgent: String = MediaHubUserAgent.DEFAULT,
) {

    /** 解析某个服务器应使用的 UA。serverId 为空/未知/配置非法 → 默认 UA。 */
    fun forServer(serverId: String?): String {
        val id = serverId?.trim().orEmpty()
        if (id.isEmpty()) return defaultUserAgent
        val raw = overrides()[id] ?: return defaultUserAgent
        return UserAgentValidator.validate(raw).value ?: defaultUserAgent
    }

    /** 当前是否对某服务器存在生效的自定义 UA（用于诊断展示，不泄露其它信息）。 */
    fun hasCustomUserAgent(serverId: String?): Boolean {
        val id = serverId?.trim().orEmpty()
        if (id.isEmpty()) return false
        return UserAgentValidator.validate(overrides()[id]).value != null
    }
}

/** 由请求 URL 解析所属服务器；返回 null = 未知来源（例如跨 origin 重定向后的第三方站点）。 */
fun interface ServerIdResolver {
    fun serverIdFor(url: HttpUrl): String?
}

/**
 * 为每个请求（含重定向后的每一跳）写入统一 UA（M0-A / ADR-045）。
 *
 * 必须注册为 **network interceptor**：
 * - 应用拦截器只在首次请求执行一次，重定向后的新 origin 不会重新解析 serverId；
 * - network 拦截器对每一跳生效，因此重定向到第三方 CDN 时会自动回落到默认 UA，
 *   而**不会**把原服务器的自定义 UA 泄漏给无关域名。
 *
 * 安全边界：本拦截器**只写 User-Agent**，不读取、不重写任何认证头；凭据剥离仍由
 * [OriginScopedCredentialInterceptor]（ADR-030）负责，两者互不越权。
 */
class UserAgentInterceptor(
    private val policy: UserAgentPolicy,
    private val serverIdFor: ServerIdResolver,
) : okhttp3.Interceptor {

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val serverId = runCatching { serverIdFor.serverIdFor(request.url) }.getOrNull()
        val userAgent = policy.forServer(serverId)
        if (request.header(HEADER_USER_AGENT) == userAgent) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER_USER_AGENT, userAgent).build())
    }

    companion object {
        const val HEADER_USER_AGENT: String = "User-Agent"
    }
}
