package com.mediahub.core.network

import com.mediahub.core.logging.StdoutLogger
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线路探测错误脱敏回归（2C 安全收尾 REDACTION）。
 *
 * EndpointTestResult.error 直接进入编辑页 UI state。历史实现把 `e.message` 原样写入，
 * 而 OkHttp/JSSE 的异常消息可能携带 URL user-info、代理凭据或敏感 query。
 * 本文件用**运行时合成秘密标记**覆盖：普通异常、嵌套 cause、编码 URL（user-info 与
 * %XX 编码）、各类 IOException 的分类可操作性，以及取消透传不被映射吞掉。
 *
 * CANCEL（取消传播/Call 终止/资源释放）由 EndpointTestServiceCancellationTest
 * 与 EndpointTestServiceOwnershipTest 单独覆盖，两者结论分开记录。
 */
class EndpointTestServiceRedactionTest {

    // 与业务无关的合成标记：任何出现即视为泄漏
    private val secrets = listOf(
        "Sup3rS3cret-Marker",
        "token=abc123xyz-marker",
        "Authorization:%20Bearer%20sk-marker",
    )

    private fun assertNoLeak(error: String?, where: String) {
        for (secret in secrets) {
            assertFalse("$where 泄漏合成秘密 [$secret]: $error", error?.contains(secret) == true)
        }
    }

    /** 注入点：newCall 阶段直接抛出受控异常，走 API 层错误映射路径。 */
    private fun service(failure: IOException): EndpointTestService {
        val factory = HttpClientFactory(StdoutLogger())
        return object : EndpointTestService(factory) {
            override fun newCall(client: OkHttpClient, request: Request): Call {
                throw failure
            }
        }
    }

    private fun resultFor(failure: IOException): EndpointTestResult = runBlocking {
        service(failure).test("http://10.255.255.1:1", "/probe")
    }

    @Test
    fun `plain exception message with synthetic secrets never reaches the result`() {
        val failure = IOException(
            "Failed to connect to http://admin:Sup3rS3cret-Marker@10.0.0.9:8096/emby?token=abc123xyz-marker",
        )
        val result = resultFor(failure)

        assertEquals("API test failed: 网络错误", result.error)
        assertNoLeak(result.error, "普通异常消息")
    }

    @Test
    fun `nested cause chain secrets never reach the result`() {
        val failure = IOException(
            "outer looks benign",
            java.net.SocketException("inner: password=Sup3rS3cret-Marker Authorization: Bearer sk-marker"),
        )
        val result = resultFor(failure)

        // 分类按抛出异常本身的类型（外层普通 IOException），不穿透 cause 取类型；
        // 关键契约：cause 链中的秘密与外层消息都不得进入结果
        assertEquals("API test failed: 网络错误", result.error)
        assertNoLeak(result.error, "嵌套 cause")
        assertFalse("外层消息也不得进入结果", result.error!!.contains("outer looks benign"))
    }

    @Test
    fun `encoded url with user-info and token query never reaches the result`() {
        val failure = java.net.UnknownHostException(
            "unable to resolve host%3A%20http%3A%2F%2Fuser%3ASup3rS3cret-Marker%40host%2Femby%3Ftoken%3Dabc123xyz-marker",
        )
        val result = resultFor(failure)

        assertEquals("API test failed: 无法解析服务器地址（请检查域名）", result.error)
        assertNoLeak(result.error, "编码 URL")
    }

    @Test
    fun `exception categories stay actionable and free of raw messages`() {
        val cases = listOf(
            UnknownHostException("dns broke for Sup3rS3cret-Marker") to
                "API test failed: 无法解析服务器地址（请检查域名）",
            ConnectException("refused Sup3rS3cret-Marker") to
                "API test failed: 无法连接到服务器（请检查地址与端口）",
            SocketTimeoutException("timed out Sup3rS3cret-Marker") to
                "API test failed: 连接超时",
            SSLException("handshake Sup3rS3cret-Marker token=abc123xyz-marker") to
                "API test failed: 安全连接（TLS）失败",
        )
        for ((failure, expected) in cases) {
            val result = resultFor(failure)
            assertEquals("分类必须可操作（${failure.javaClass.simpleName}）", expected, result.error)
            assertNoLeak(result.error, failure.javaClass.simpleName)
        }
    }

    @Test
    fun `cancellation is never converted into a mapped error result`() = runBlocking {
        // describe() 只在非取消路径被调用；本断言锁定映射器自身不吞取消语义：
        // CancellationException 不属于任何 IOException 分类，describe 不得把它的
        // 消息（测试框架取消消息可能含协程名）当成可用文案输出。
        val cancel = CancellationException("StandaloneCoroutine was cancelled; token=abc123xyz-marker")
        val mapped = EndpointProbeErrorMapper.describe(IOException("x").initCause(cancel))

        assertFalse(mapped.contains("token=abc123xyz-marker"))
        assertTrue(mapped.startsWith(EndpointProbeErrorMapper.API_FAILURE_PREFIX))
    }
}
