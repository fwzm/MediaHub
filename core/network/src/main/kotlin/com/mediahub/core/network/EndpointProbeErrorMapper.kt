package com.mediahub.core.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 线路探测错误的安全分类映射（Phase 1I 2C 安全收尾）。
 *
 * [EndpointTestResult.error] 会进入编辑页 UI state 并直接展示给用户；
 * 原始 `e.message` 可能携带 URL user-info、代理凭据、Token query 或底层实现细节
 * （且随 OkHttp/JSSE 版本变化不可控），因此本映射**只输出分类文案，绝不携带
 * 异常消息与 cause 链**。分类保留可操作性：用户能据此区分地址无效、
 * DNS 解析失败、连不上、超时与 TLS 故障。
 *
 * 与 [com.mediahub.core.logging.Redactor] 的关系：Redactor 是"带原文本时抹敏感值"，
 * 本映射是"源头不产生原文本"——两者不互替。
 */
internal object EndpointProbeErrorMapper {

    /** API 层失败文案前缀（媒体层失败按既有契约不产生错误文案）。 */
    const val API_FAILURE_PREFIX = "API test failed: "

    fun describe(e: Throwable): String = API_FAILURE_PREFIX + when (e) {
        is UnknownHostException -> "无法解析服务器地址（请检查域名）"
        is ConnectException -> "无法连接到服务器（请检查地址与端口）"
        is SocketTimeoutException -> "连接超时"
        is SSLException -> "安全连接（TLS）失败"
        is SocketException, is InterruptedIOException -> "连接中断"
        is IOException -> "网络错误"
        // 请求构造阶段失败（如 OkHttp 拒绝的非法主机/端口）；消息可能内嵌 URL 片段，不外传
        else -> "请求无法发起（地址可能无效）"
    }
}
