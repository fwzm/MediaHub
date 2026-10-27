package com.mediahub.feature.settings.pairing

import com.mediahub.core.common.pairing.PairingChallenge
import com.mediahub.core.common.pairing.PairingCardExchangeResult
import com.mediahub.core.common.pairing.PairingFingerprint
import com.mediahub.core.common.pairing.PairingInvitationResult
import com.mediahub.core.common.pairing.PairingPeerCard
import com.mediahub.core.common.pairing.PairingTlsOptions
import com.mediahub.core.common.pairing.PairingTransportClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 拉取一次配对邀请的出口（W6-59b，纯 Kotlin 便于替换）。
 *
 * 参数是「已经核对过的链接」里写明的三样东西：对端地址、这次邀请的挑战、对端的公钥指纹。
 * 指纹同时被当作 TLS 钉扎的信任根——调用方不需要另外决定信谁。
 */
fun interface PairingFetchPort {

    suspend fun fetch(
        host: String,
        port: Int,
        expectedChallenge: PairingChallenge,
        expectedFingerprint: PairingFingerprint,
    ): PairingInvitationResult
    /**
     * 带身份卡片交换的拉取（W6-71）。
     *
     * 默认实现退化成单向拉取：拿不到对端卡片时这次只是「记不住对端」，
     * 已经校验通过的邀请仍然是成功结果。只有真拨号的实现才覆盖它。
     */
    suspend fun fetchWithCard(
        host: String,
        port: Int,
        expectedChallenge: PairingChallenge,
        expectedFingerprint: PairingFingerprint,
        selfCard: PairingPeerCard?,
    ): PairingCardExchangeResult = when (
        val outcome = fetch(host, port, expectedChallenge, expectedFingerprint)
    ) {
        is PairingInvitationResult.Ok -> PairingCardExchangeResult.Ok(outcome.payload, outcome.json, null)
        is PairingInvitationResult.Failed -> PairingCardExchangeResult.Failed(outcome.reason, outcome.message)
    }
}

/**
 * 生产实现：真拨号 + TLS 1.3 + SPKI 指纹钉扎（W6-59b）。
 *
 * 钉扎用的一定是链接里那一枚指纹（[expectedFingerprint]），不额外接受调用方另给的指纹：
 * 链接就是信任根，多一个来源只会多一个可被替换的地方。
 */
class PairingTransportFetchPort(
    private val client: PairingTransportClient = PairingTransportClient(),
) : PairingFetchPort {

    override suspend fun fetch(
        host: String,
        port: Int,
        expectedChallenge: PairingChallenge,
        expectedFingerprint: PairingFingerprint,
    ): PairingInvitationResult = withContext(Dispatchers.IO) {
        client.fetchInvitation(
            host = host,
            port = port,
            expectedChallenge = expectedChallenge,
            expectedFingerprint = expectedFingerprint,
            tls = PairingTlsOptions.Pinned(expectedFingerprint.hex),
        )
    }

    /**
     * 真拨号版本：先读邀请并逐项校验，校验通过后才写本机卡片、再尽力读对端卡片（W6-71）。
     *
     * 卡片仍然只是附加信息：对端是没有登记簿的老构建、或者读完邀请就关连接，
     * 都只让 peerCard 为 null，不影响邀请本身的结论。
     */
    override suspend fun fetchWithCard(
        host: String,
        port: Int,
        expectedChallenge: PairingChallenge,
        expectedFingerprint: PairingFingerprint,
        selfCard: PairingPeerCard?,
    ): PairingCardExchangeResult = withContext(Dispatchers.IO) {
        client.fetchInvitationWithCard(
            host = host,
            port = port,
            expectedChallenge = expectedChallenge,
            expectedFingerprint = expectedFingerprint,
            selfCard = selfCard,
            tls = PairingTlsOptions.Pinned(expectedFingerprint.hex),
        )
    }
}
