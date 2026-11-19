package com.mediahub.core.common

import com.mediahub.model.ServerType

/**
 * 媒体服务器裸主机地址的候选端口探测（#75 部分）：
 *
 * - 显式端口或反代子路径**不扩展**：用户已经指定了落点，擅自换端口/路径会掩盖输入错误。
 * - 裸主机先试规范化地址（协议默认端口），再按当前协议偏好顺序试 Emby/Jellyfin 常用端口：
 *   https 8920、http 8096。两条候选都经 [ServerAddressNormalizer] 校验，IPv6 方括号原样保留。
 * - 其他数据源类型只返回规范化地址，绝不拿媒体服务器端口去探测 WebDAV/网盘。
 */
object ServerAddressAutoDetect {

    data class Candidate(val url: String, val scheme: String, val port: Int?)

    fun candidates(
        rawInput: String,
        preferHttps: Boolean,
        serverType: ServerType,
    ): List<Candidate> {
        val normalized = ServerAddressNormalizer.normalize(rawInput, preferHttps) as? ServerAddressNormalizer.Result.Ok
            ?: return emptyList()
        val address = normalized.address
        val initial = Candidate(address.url, address.scheme, address.port)
        if (address.port != null || address.path.isNotEmpty()) return listOf(initial)
        if (serverType != ServerType.EMBY && serverType != ServerType.JELLYFIN) return listOf(initial)

        val mediaPorts = if (preferHttps) {
            listOf("https" to 8920, "http" to 8096)
        } else {
            listOf("http" to 8096, "https" to 8920)
        }
        val mediaCandidates = mediaPorts.mapNotNull { (scheme, port) ->
            val raw = scheme + "://" + address.host + ":" + port
            (ServerAddressNormalizer.normalize(raw, scheme == "https") as? ServerAddressNormalizer.Result.Ok)
                ?.address
                ?.let { Candidate(it.url, it.scheme, it.port) }
        }
        return (listOf(initial) + mediaCandidates).distinctBy { it.url }
    }
}
