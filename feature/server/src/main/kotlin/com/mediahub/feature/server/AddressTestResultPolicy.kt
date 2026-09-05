package com.mediahub.feature.server

/**
 * 异步连接测试结果与地址草稿版本绑定（Phase 1I）：
 * 请求发起后地址或协议开关发生过任何变化（版本递增），迟到的结果不得覆盖当前
 * 状态——旧地址的成功结果不能伪装成新地址的测试通过。
 */
object AddressTestResultPolicy {
    fun shouldApply(currentVersion: Long, requestVersion: Long): Boolean =
        currentVersion == requestVersion
}
