package com.mediahub.core.common.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 同步层地址规范化的端口范围闸门（ADR-S001 D4 的补强）。
 *
 * 缺陷：基线只在 http(s) 分支判 `port <= 0`，**没有上限**。`java.net.URI` 对 `:65536` / `:70000`
 * 这类越界端口照收不误（只有解析不出数字时才返回 -1），于是规范化会把一个**根本拨不出去**的
 * 端口写进身份串。后果是双向的：
 * - 同一个服务器写成 `host:65536` 与 `host:65536/` 之外的任何形式都不会与真实地址归一，去重/比对永远不相等；
 * - 更糟的是身份串本身看起来合法，故障被推迟到真正建连时才以「连接失败」暴露，排查时看不出是地址写错。
 *
 * 约定：端口必须落在 1..65535；越界（含 0 与 >65535）一律返回 null（= 没有可用身份），
 * 与「不是可拨号的绝对地址」同一语义，不新造返回类型。
 */
class SyncUrlNormalizerPortRangeTest {

    @Test
    fun `a port above the tcp range is rejected instead of becoming an identity`() {
        assertNull(SyncUrlNormalizer.normalize("http://nas:65536/emby"))
        assertNull(SyncUrlNormalizer.normalize("https://nas:70000/gate"))
        assertNull(SyncUrlNormalizer.normalize("http://nas:99999999"))
    }

    @Test
    fun `port zero is rejected for every supported scheme`() {
        assertNull(SyncUrlNormalizer.normalize("http://nas:0/emby"))
        assertNull(SyncUrlNormalizer.normalize("smb://nas:0/share"))
    }

    @Test
    fun `the last legal port is still accepted`() {
        assertEquals("http://nas:65535/emby", SyncUrlNormalizer.normalize("http://nas:65535/emby"))
        assertEquals("smb://nas:65535/share", SyncUrlNormalizer.normalize("smb://nas:65535/share"))
    }

    @Test
    fun `the first legal port is still accepted`() {
        assertEquals("http://nas:1/emby", SyncUrlNormalizer.normalize("http://nas:1/emby"))
    }

    @Test
    fun `an out of range port is not silently folded into the scheme default`() {
        // 关键回归：越界端口绝不能被"修好"成默认端口——那会把用户写错的地址伪装成合法身份。
        val normalized = SyncUrlNormalizer.normalize("https://nas:70000/gate")
        assertNull(normalized)
        assertFalse(normalized == "https://nas:443/gate")
    }

    @Test
    fun `an ipv6 literal with an out of range port is rejected too`() {
        assertNull(SyncUrlNormalizer.normalize("http://[::1]:65536/emby"))
        assertEquals("http://[::1]:8096/emby", SyncUrlNormalizer.normalize("http://[::1]:8096/emby"))
    }
}
