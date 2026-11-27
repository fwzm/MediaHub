package com.mediahub.provider.api

/**
 * Provider 错误的**用户可读文案**（审计条目 83）。
 *
 * 背景：Emby/Jellyfin 的非认证错误此前在"文件源管理"里显示泛化文案（如裸 `HTTP 403`），
 * 用户无法判断该改密码、改地址还是找管理员。这里把 [ProviderException.ErrorCode]
 * （以及 HTTP 状态码）统一映射为准确、可操作的文案。
 *
 * 硬约束：
 * - **禁止外显原始 message**（可能携带 URL/Token/实现细节），所有文本都是固定文案；
 * - **403 ≠ 密码错误**：403 是访问策略拒绝（账号禁用/不允许远程访问/IP 白名单/权限不足），
 *   401 才是登录过期。两者文案必须可被用户区分（见 [fromHttpStatus]）。
 */
object ProviderErrorText {

    /** 数据库里可能残留的历史错误文本（已消毒但仍是实现口径）。 */
    private val HTTP_STATUS_REGEX = Regex("""(?:HTTP|服务器返回)\s*(\d{3})""")

    /** 结构化异常 → 用户文案（按子类，保留本地目录等专属语义）。 */
    fun from(e: ProviderException): String = when (e) {
        is ProviderException.DirectoryAccessRequired ->
            "本地目录授权失效或不可访问，请重新选择目录"
        is ProviderException.AuthRequired -> "需要登录后才能访问，请重新登录"
        is ProviderException.AuthFailed -> "用户名或密码错误"
        is ProviderException.AuthExpired -> AUTH_EXPIRED_TEXT
        is ProviderException.Http -> fromHttpStatus(e.statusCode)
        is ProviderException.Network -> networkText(e.cause)
        is ProviderException.Parse -> "服务器响应无法解析，可能不是受支持的媒体服务器"
        is ProviderException.NotFound -> "服务器上找不到请求的内容（404）"
        is ProviderException.RateLimited -> RATE_LIMITED_TEXT
        is ProviderException.Connection -> "无法连接到服务器，请检查地址与端口"
        is ProviderException.NotYetImplemented -> "当前数据源暂不支持该操作"
        is ProviderException.Unknown -> "未知错误，请稍后重试或查看诊断日志"
    }

    /** 只有 [ProviderException.ErrorCode]（+ 可选 HTTP 状态码）时的文案。 */
    fun fromCode(code: ProviderException.ErrorCode, httpStatus: Int? = null): String = when (code) {
        ProviderException.ErrorCode.AUTH_REQUIRED -> "需要登录后才能访问，请重新登录"
        ProviderException.ErrorCode.AUTH_FAILED -> "用户名或密码错误"
        ProviderException.ErrorCode.AUTH_EXPIRED -> AUTH_EXPIRED_TEXT
        ProviderException.ErrorCode.NETWORK -> "网络连接失败，请检查网络与服务器地址"
        ProviderException.ErrorCode.HTTP -> fromHttpStatus(httpStatus ?: 0)
        ProviderException.ErrorCode.PARSE -> "服务器响应无法解析，可能不是受支持的媒体服务器"
        ProviderException.ErrorCode.NOT_FOUND -> "服务器上找不到请求的内容（404）"
        ProviderException.ErrorCode.RATE_LIMITED -> RATE_LIMITED_TEXT
        ProviderException.ErrorCode.CONNECTION -> "无法连接到服务器，请检查地址与端口"
        ProviderException.ErrorCode.NOT_IMPLEMENTED -> "当前数据源暂不支持该操作"
        ProviderException.ErrorCode.UNKNOWN -> "未知错误，请稍后重试或查看诊断日志"
    }

    /**
     * 卡片上的"上次错误"文本（历史落库字符串）→ 安全文案。
     * 能识别出状态码/关键词就给出准确文案；否则给固定兜底，
     * **绝不把原始字符串直接展示给用户**。
     */
    fun fromStoredLastError(raw: String): String {
        val status = HTTP_STATUS_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        if (status != null) return fromHttpStatus(status)
        return when {
            raw.contains("超时") -> "连接超时，请检查网络与服务器地址"
            raw.contains("网络") || raw.contains("连接") -> "网络连接失败，请检查网络与服务器地址"
            raw.contains("解析") -> "服务器响应无法解析，可能不是受支持的媒体服务器"
            raw.contains("频繁") || raw.contains("限流") -> fromHttpStatus(HTTP_TOO_MANY_REQUESTS)
            raw.contains("过期") || raw.contains("登录") -> fromHttpStatus(HTTP_UNAUTHORIZED)
            raw.contains("未找到") -> "服务器上找不到请求的内容（404）"
            raw.contains("未实现") || raw.contains("尚未支持") -> "当前数据源暂不支持该操作"
            else -> "上次连接失败，请打开该媒体源查看详情"
        }
    }

    private fun networkText(cause: Throwable?): String {
        // ProviderException 的 cause 已消毒为 "<原类名> (sanitized)"：
        // 类名保留分类线索（DNS/拒连/超时），原始文本不外泄。
        val messages = generateSequence(cause) { it.cause }.mapNotNull { it.message }.toList()
        return when {
            messages.any { it.startsWith("UnknownHostException") } ->
                "无法解析服务器地址（DNS），请检查域名拼写，或在局域网内直接使用 IP"
            messages.any { it.startsWith("ConnectException") } ->
                "服务器拒绝建立连接，请确认端口已开放且未被防火墙拦截"
            messages.any { it.startsWith("SocketTimeoutException") } ->
                "连接超时，请检查网络与服务器地址"
            else -> "网络连接失败，请检查网络与服务器地址"
        }
    }

    private fun fromHttpStatus(status: Int): String = when {
        status == HTTP_UNAUTHORIZED -> "登录已过期（HTTP 401），请重新登录"
        status == HTTP_FORBIDDEN ->
            "服务器拒绝访问（HTTP 403）——这不是密码错误：" +
                "请检查账号是否被禁用、是否允许远程访问，以及反向代理白名单"
        status == HTTP_NOT_FOUND -> "服务器上找不到该地址（HTTP 404），请检查地址、端口或路径"
        status == HTTP_TOO_MANY_REQUESTS -> "请求过于频繁（HTTP 429），请稍后重试"
        status in 500..599 -> "服务器内部错误（HTTP $status），请稍后重试"
        status > 0 -> "请求失败（HTTP $status）"
        else -> "请求失败"
    }

    private const val AUTH_EXPIRED_TEXT = "登录状态已过期，请重新登录"
    private const val RATE_LIMITED_TEXT = "请求过于频繁（429），请稍后重试"

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_TOO_MANY_REQUESTS = 429
}
