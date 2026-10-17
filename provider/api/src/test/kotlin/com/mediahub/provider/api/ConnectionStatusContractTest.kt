package com.mediahub.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 · `ConnectionStatus.errorCode` 的**可用性契约**（先红后绿）。
 *
 * 现状（核验结论）：`errorCode` 字段在 API 里定义、WebDAV 会设置，
 * 但 **Emby / Jellyfin 从不设置**，且 **UI 只看 message + ok，从不读 errorCode**。
 * 这是一个「只定义、不使用」的死字段 —— 与 M16 的 `recordUnobservable` 同类。
 *
 * 两条契约：
 * 1. 每个 provider 的失败状态必须**填** errorCode（否则下游无从结构化判断）；
 * 2. `ConnectionStatus.toActionableHint()` 必须按 errorCode 给出**可操作**指引，
 *    且对 403 / 401 / 网络 / 未知 给出**不同**的指引（不能退化成同一句话）。
 */
class ConnectionStatusContractTest {

    @Test
    fun `auth required is actionable and distinct from generic http`() {
        val auth = ConnectionStatus(
            ok = false,
            message = "x",
            errorCode = ProviderException.ErrorCode.AUTH_REQUIRED,
        )
        val http = ConnectionStatus(
            ok = false,
            message = "x",
            errorCode = ProviderException.ErrorCode.HTTP,
        )
        val authHint = auth.toActionableHint()
        val httpHint = http.toActionableHint()
        assertNotNull("AUTH_REQUIRED 必须有指引", authHint)
        assertNotNull("HTTP 必须有指引", httpHint)
        assertTrue("两者指引必须不同（否则 errorCode 没有意义）", authHint != httpHint)
    }

    @Test
    fun `a successful status has no error hint`() {
        assertNull(
            "成功的连接不应显示错误指引",
            ConnectionStatus(ok = true, message = "ok").toActionableHint(),
        )
    }

    @Test
    fun `unknown error code still yields a safe generic hint`() {
        val hint = ConnectionStatus(ok = false, message = "x").toActionableHint()
        assertEquals(
            "未设置 errorCode 时必须给出保守的通用指引（不能抛异常、不能返回 null 让 UI 空着）",
            "请检查服务器地址与网络连接",
            hint,
        )
    }
}
