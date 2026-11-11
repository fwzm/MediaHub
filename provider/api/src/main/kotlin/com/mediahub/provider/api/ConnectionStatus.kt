package com.mediahub.provider.api

/** 连通性状态（"测试连接"/"线路状态"展示用）。 */
data class ConnectionStatus(
    val ok: Boolean,
    val latencyMs: Long? = null,
    val message: String? = null,
    val errorCode: ProviderException.ErrorCode? = null,
) {
    /**
     * 按 [errorCode] 给出**可操作**的下一步指引（P3）。
     *
     * 为什么需要它：此前 `errorCode` 是一个「只定义、不使用」的字段 ——
     * WebDAV 会填、Emby/Jellyfin 从不填、UI 只看 `message`。
     * 于是"需要登录"与"权限被拒"在用户眼里没有区别，而 [message] 是自由文本，
     * 无法稳定驱动 UI 的差异化引导。
     *
     * 返回 `null` 表示成功，无需指引。未设置 errorCode 时返回保守的通用指引
     * （**不返回 null**：UI 不应在失败时空着没有任何下一步）。
     */
    fun toActionableHint(): String? {
        if (ok) return null
        return when (errorCode) {
            ProviderException.ErrorCode.AUTH_REQUIRED ->
                "需要登录：请先完成账号登录"
            ProviderException.ErrorCode.AUTH_FAILED ->
                "账号或密码不正确：请核对后重试"
            ProviderException.ErrorCode.AUTH_EXPIRED ->
                "登录状态已过期：请重新登录"
            ProviderException.ErrorCode.NETWORK ->
                "网络不可达：请检查网络连接与服务器地址"
            ProviderException.ErrorCode.CONNECTION ->
                "连接失败：请确认服务器地址与端口，以及是否允许远程访问"
            ProviderException.ErrorCode.HTTP ->
                "服务器拒绝访问：这不是密码错误，请检查该账号权限 / 远程访问 / IP 白名单"
            ProviderException.ErrorCode.PARSE ->
                "服务器响应无法解析：该地址可能不是对应的媒体服务器"
            ProviderException.ErrorCode.NOT_FOUND ->
                "路径不存在：请检查服务器地址是否包含正确的前缀"
            ProviderException.ErrorCode.RATE_LIMITED ->
                "请求过于频繁：请稍后再试"
            ProviderException.ErrorCode.NOT_IMPLEMENTED ->
                "该操作尚未支持"
            ProviderException.ErrorCode.UNKNOWN, null ->
                "请检查服务器地址与网络连接"
        }
    }

    companion object {
        val Unknown = ConnectionStatus(ok = false, message = "未知")
        val Testing = ConnectionStatus(ok = false, message = "测试中…")
    }
}
