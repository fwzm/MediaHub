package com.mediahub.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WebDAV itemId（同 origin 绝对 URL）在导航参数中的往返回归。
 *
 * 这类 id 含 `/`（路由分隔符）、中文/空格（原始或已 percent-encode）、`+`、`%`、
 * `#`（query/fragment 分隔符）与多级目录；Base64(URL_SAFE, 无填充) 必须对它们
 * 双向无损，且输出自身不含 `/`、`+`、`=`（可安全作为路径段）。
 */
class NavArgCodecWebDavIdTest {

    @Test
    fun `webdav absolute urls round-trip losslessly`() {
        val ids = listOf(
            "http://192.168.1.10:5000/dav/%E7%94%B5%E5%BD%B1%20a%2Bb%2050%25%20%23test.mkv",
            "http://nas.local:5005/dav/电影/第一季/第 1 集 + 花絮.mkv",
            "https://nas.local:5006/dav/a/b/c/d/e/deep.mkv",
            "http://10.0.0.2/dav/trailing+plus%2Band%25percent.mkv",
            "http://10.0.0.2/dav/",
        )
        for (id in ids) {
            assertEquals("往返必须无损: $id", id, NavArgCodec.decode(NavArgCodec.encode(id)))
        }
    }

    @Test
    fun `encoded form is safe as a navigation path segment`() {
        val encoded = NavArgCodec.encode("http://10.0.0.2/dav/a b/中文+c.mkv")
        assertEquals("输出不得含路由分隔符 /", false, encoded.contains('/'))
        assertEquals("输出不得含 +（URL_SAFE 变体）", false, encoded.contains('+'))
        assertEquals("无填充：输出不得含 =", false, encoded.contains('='))
        assertEquals("输出只含 URL-safe 字母表", 0, encoded.count { it !in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" })
    }

    @Test
    fun `distinguishes ids that only differ after decoding`() {
        val a = "http://10.0.0.2/dav/a%2Fb.mkv"
        val b = "http://10.0.0.2/dav/a/b.mkv"
        assertEquals(false, NavArgCodec.encode(a) == NavArgCodec.encode(b))
        assertEquals(a, NavArgCodec.decode(NavArgCodec.encode(a)))
        assertEquals(b, NavArgCodec.decode(NavArgCodec.encode(b)))
    }
}
