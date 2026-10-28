package com.mediahub.feature.settings.pairing

import com.mediahub.core.common.pairing.PairingAddressCandidate
import com.mediahub.core.common.pairing.PairingAddressReasons
import com.mediahub.core.common.pairing.PairingAddressReport

/**
 * 「本机可被连的地址」区块的文案（W6-60）。
 *
 * 这一块的定位与 Windows 的 PairingAddressCandidates 完全一致：只把候选地址变成给人看的文字，
 * 不监听、不拨号、不写凭据。地址行只给「地址 + 接口 + 有线/无线」，**不拼 URL**——
 * 端口该用哪个由发起同步的那一页决定，这里不替它编一个。
 */
object PairingAddressSummary {

    const val TITLE: String = "本机可被连的地址"

    /** 这一块到底做什么、不做什么（页面与测试共用）。 */
    const val DISPLAY_ONLY_HINT: String = "这一块只做展示：不监听端口、不写凭据、不建立任何连接；" +
        "要传文件请到来源同步页单独发起。"

    const val REFRESH: String = "重新读取本机地址"

    /** 还没读过（首帧，读盘是同步的，正常看不到）。 */
    const val NOT_READ_HINT: String = "还没读取本机地址。"

    /** 本次构建没有可用的枚举出口（界面预览态或未接线）：不读，也不假装读到空。 */
    const val UNAVAILABLE_HINT: String = "这个页面当前没有可用的地址读取出口（预览态或未接线），没有读网卡。"

    /** 概况句：有候选就说几个、首选是谁；没有就按稳定码说清是「没地址」还是「读不到」。 */
    fun lineOf(report: PairingAddressReport): String {
        val primary = report.primary
        if (primary == null) {
            return when (report.reason) {
                PairingAddressReasons.ENUMERATION_FAILED ->
                    "读不到本机网卡（" + PairingAddressReasons.ENUMERATION_FAILED + "）：这一块没有可展示的地址。"
                PairingAddressReasons.NO_USABLE_ADDRESS ->
                    "本机没有可用的局域网地址（" + PairingAddressReasons.NO_USABLE_ADDRESS + "）。"
                else -> "本机没有可用的局域网地址。"
            }
        }
        return "本机有 " + report.count + " 个可用地址，首选 " + primary.address +
            "（" + primary.interfaceName + "，" + kindLabelOf(primary) + "）。"
    }

    /** 每个候选一行：地址（接口，有线/无线）。不含端口，不含 URL。 */
    fun linesOf(report: PairingAddressReport): List<String> =
        report.candidates.map { candidate ->
            candidate.address + "（" + candidate.interfaceName + "，" + kindLabelOf(candidate) + "）"
        }

    /** 概况行的稳定码（有候选时为空），供 UI 测试与排障直接断言。 */
    fun codeOf(report: PairingAddressReport): String? =
        if (report.isEmpty) report.reason else null

    private fun kindLabelOf(candidate: PairingAddressCandidate): String = when (candidate.reason) {
        PairingAddressReasons.WIRED -> "有线"
        PairingAddressReasons.WIRELESS -> "无线"
        else -> "其它接口"
    }
}
