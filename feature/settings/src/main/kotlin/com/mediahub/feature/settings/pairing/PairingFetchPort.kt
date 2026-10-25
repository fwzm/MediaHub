package com.mediahub.feature.settings.pairing

import com.mediahub.core.common.pairing.PairingChallenge
import com.mediahub.core.common.pairing.PairingFingerprint
import com.mediahub.core.common.pairing.PairingInvitationResult
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
}
