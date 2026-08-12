package com.mediahub.provider.api

/** 连通性状态（"测试连接"/"线路状态"展示用）。 */
data class ConnectionStatus(
    val ok: Boolean,
    val latencyMs: Long? = null,
    val message: String? = null,
    val errorCode: ProviderException.ErrorCode? = null,
) {
    companion object {
        val Unknown = ConnectionStatus(ok = false, message = "未知")
        val Testing = ConnectionStatus(ok = false, message = "测试中…")
    }
}

/** 一次连接探测的临时上下文；敏感字段仅存活于内存。 */
data class ConnectionTestRequest(
    val credentials: Credentials? = null,
    val parameters: Map<String, String> = emptyMap(),
)
