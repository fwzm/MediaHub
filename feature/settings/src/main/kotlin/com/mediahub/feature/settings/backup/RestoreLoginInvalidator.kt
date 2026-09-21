package com.mediahub.feature.settings.backup

/**
 * 恢复时的登录态隔离（Phase 1I review：界面提示"需重新登录"不构成隔离）。
 *
 * 当替换目标媒体源的身份（类型/地址/账号归属）发生变化时，必须先阻断旧
 * 登录态继续使用——按 serverId 清除 Token 与 Provider 会话，再执行数据库写入。
 * 生产实现绑定 TokenStore + 各 Provider 会话存储（app 模块装配）。
 */
fun interface RestoreLoginInvalidator {
    /** 清除指定媒体源的全部登录态；失败可重试（幂等）。 */
    suspend fun invalidate(serverId: String)

    /** Keep affected authentication blocked through durable data writes and any rollback. */
    suspend fun <T> withIdentityChange(serverIds: Set<String>, block: suspend () -> T): T = block()
}
