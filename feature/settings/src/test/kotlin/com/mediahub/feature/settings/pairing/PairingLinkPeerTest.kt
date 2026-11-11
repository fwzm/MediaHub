package com.mediahub.feature.settings.pairing

import com.mediahub.core.common.pairing.InMemoryPairedDeviceStore
import com.mediahub.core.common.pairing.PairedDevice
import com.mediahub.core.common.pairing.PairedDeviceRegistry
import com.mediahub.core.common.pairing.PairedFingerprintState
import com.mediahub.core.common.pairing.PairedDeviceReasons
import com.mediahub.core.common.pairing.PairingCardExchangeResult
import com.mediahub.core.common.pairing.PairingChallenge
import com.mediahub.core.common.pairing.PairingFingerprint
import com.mediahub.core.common.pairing.PairingInvitationResult
import com.mediahub.core.common.pairing.PairingLink
import com.mediahub.core.common.pairing.PairingPayload
import com.mediahub.core.common.pairing.PairingPeerCard
import com.mediahub.core.common.pairing.PairingPayloadCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-71：「对端卡片核对 + 显式登记」的 JVM 证据（纯 JVM，不开设备、不碰 Compose）。
 *
 * 这一层要钉住的是四件事：
 * 1. 拉邀请成功后拿到对端卡片只做**只读比对**，界面上出现 Reviewed，但登记簿一个字节都没写；
 * 2. 只有用户显式点「登记这台设备」才写盘，写的是**卡片里的稳定指纹**（不是邀请里的临时证书指纹）；
 * 3. 已登记设备换指纹一律 Mismatch，按钮不可点、登记被拒、**已钉住的指纹绝不被覆盖**（与 Windows 同一条边界）；
 * 4. 卡片拿不到（老构建）、本机没有登记簿、拉邀请失败，都只是「这次记不住对端」，
 *    不会把已经校验通过的邀请变成失败，也不会泄漏挑战或原始链接。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingLinkPeerTest {

    private val issuedAt: Long = 1_760_000_000_000L

    private val challenge: PairingChallenge = PairingChallenge.createRandom()

    private val inviteFingerprint: PairingFingerprint =
        PairingFingerprint.fromBytes(ByteArray(PairingFingerprint.BYTE_LENGTH) { index -> (index + 7).toByte() })

    /** 对端卡片里的**稳定**指纹：与邀请里的临时证书指纹是两回事。 */
    private val peerFingerprint: PairingFingerprint =
        PairingFingerprint.fromBytes(ByteArray(PairingFingerprint.BYTE_LENGTH) { index -> (index + 91).toByte() })

    /** 本机身份卡片的指纹（自己这一侧，与对端无关）。 */
    private val selfFingerprint: PairingFingerprint =
        PairingFingerprint.fromBytes(ByteArray(PairingFingerprint.BYTE_LENGTH) { index -> (index + 33).toByte() })

    private fun payload(
        deviceId: String = "windows-a",
        nowEpochMs: Long = issuedAt,
    ): PairingPayload = PairingPayload.create(
        deviceId = deviceId,
        ephemeralUrl = "https://192.168.1.20:8443/pairing",
        challenge = challenge,
        publicKeyFingerprint = inviteFingerprint,
        nowEpochMs = nowEpochMs,
    )

    private fun card(
        deviceId: String = "windows-a",
        displayName: String = "客厅 Windows",
        fingerprint: PairingFingerprint = peerFingerprint,
        sourceCount: Int = 3,
    ): PairingPeerCard = PairingPeerCard(
        deviceId = deviceId,
        deviceFingerprint = fingerprint.hex,
        displayName = displayName,
        sourceCount = sourceCount,
    )

    private fun registryAt(store: InMemoryPairedDeviceStore = InMemoryPairedDeviceStore()): PairedDeviceRegistry =
        PairedDeviceRegistry(store)

    /** 拉邀请的替身：只覆盖带卡片的那条路，记录调用参数与自报的卡片。 */
    private class CardFetchPort(
        private val answer: () -> PairingCardExchangeResult,
    ) : PairingFetchPort {

        var calls: Int = 0

        var selfCard: PairingPeerCard? = null

        var selfCardSeen: Boolean = false

        override suspend fun fetch(
            host: String,
            port: Int,
            expectedChallenge: PairingChallenge,
            expectedFingerprint: PairingFingerprint,
        ): PairingInvitationResult = error("W6-71 的用例只应走 fetchWithCard")

        override suspend fun fetchWithCard(
            host: String,
            port: Int,
            expectedChallenge: PairingChallenge,
            expectedFingerprint: PairingFingerprint,
            selfCard: PairingPeerCard?,
        ): PairingCardExchangeResult {
            calls += 1
            this.selfCard = selfCard
            selfCardSeen = true
            return answer()
        }
    }

    /** 只实现旧接口的替身：控制器必须退化成「拿不到卡片」而不是崩掉。 */
    private class PlainFetchPort(
        private val answer: (PairingChallenge, PairingFingerprint) -> PairingInvitationResult,
    ) : PairingFetchPort {

        override suspend fun fetch(
            host: String,
            port: Int,
            expectedChallenge: PairingChallenge,
            expectedFingerprint: PairingFingerprint,
        ): PairingInvitationResult = answer(expectedChallenge, expectedFingerprint)
    }

    private fun cardPort(card: PairingPeerCard?, payload: PairingPayload = payload()): CardFetchPort =
        CardFetchPort { PairingCardExchangeResult.Ok(payload, PairingPayloadCodec.encode(payload), card) }

    private fun controllerAt(
        nowEpochMs: Long,
        port: PairingFetchPort? = null,
        registry: PairedDeviceRegistry? = null,
        selfCard: PairingPeerCard? = null,
    ): PairingLinkController = PairingLinkController(
        now = { nowEpochMs },
        fetchPort = port,
        registry = registry,
        selfCard = selfCard,
    )

    private fun reviewed(state: PairingPeerState): PairingPeerState.Reviewed {
        assertTrue("期望已比对态，实际 " + state, state is PairingPeerState.Reviewed)
        return state as PairingPeerState.Reviewed
    }

    private fun recorded(state: PairingPeerState): PairingPeerState.Recorded {
        assertTrue("期望登记回执，实际 " + state, state is PairingPeerState.Recorded)
        return state as PairingPeerState.Recorded
    }

    @Test
    fun aCardFromAnUnknownDeviceIsReviewedButNotWritten() = runTest {
        val store = InMemoryPairedDeviceStore()
        val controller = controllerAt(issuedAt, cardPort(card()), registryAt(store))

        assertTrue(controller.verify(PairingLink.create(payload())))
        assertTrue(controller.fetchInvitation())

        val peer = reviewed(controller.peer.value)
        assertEquals("windows-a", peer.deviceId)
        assertEquals("客厅 Windows", peer.displayName)
        assertEquals(peerFingerprint.hex, peer.fingerprint)
        assertEquals(PairedFingerprintState.NEW, peer.state)
        assertEquals(PairedDeviceReasons.FINGERPRINT_NEW, peer.reason)
        assertTrue(peer.canRegister)
        assertNull(peer.pinnedFingerprint)
        // 只读比对：一个字节都没写盘。
        assertEquals(0, store.writeCount)
        assertTrue(store.knownDeviceIds.isEmpty())
    }

    @Test
    fun registeringTheReviewedCardPinsTheCardFingerprint() = runTest {
        val store = InMemoryPairedDeviceStore()
        val registry = registryAt(store)
        val controller = controllerAt(issuedAt, cardPort(card()), registry)
        assertTrue(controller.verify(PairingLink.create(payload())))
        assertTrue(controller.fetchInvitation())

        assertTrue(controller.registerPeer())

        val outcome = recorded(controller.peer.value)
        assertTrue(outcome.accepted)
        assertTrue(outcome.added)
        assertEquals(PairedDeviceReasons.REGISTERED, outcome.reason)
        assertEquals(peerFingerprint.hex, outcome.fingerprint)
        // 写进登记簿的是卡片里的稳定指纹，不是邀请里的临时证书指纹。
        val pinned = registry.find("windows-a") ?: error("登记后必须能在登记簿里查到")
        assertEquals(peerFingerprint.hex, pinned.fingerprint)
        assertFalse(pinned.fingerprint == inviteFingerprint.hex)
        assertEquals("客厅 Windows", pinned.displayName)
        assertEquals(3, pinned.sourceCount)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun aKnownDeviceWithTheSameFingerprintIsAMatch() = runTest {
        val store = InMemoryPairedDeviceStore()
        val registry = registryAt(store)
        store.save(
            PairedDevice.create(
                deviceId = "windows-a",
                fingerprint = peerFingerprint.hex,
                displayName = "客厅 Windows",
                nowEpochMs = issuedAt - 60_000L,
                sourceCount = 3,
            ),
        )
        val controller = controllerAt(issuedAt, cardPort(card()), registry)
        assertTrue(controller.verify(PairingLink.create(payload())))
        assertTrue(controller.fetchInvitation())

        val peer = reviewed(controller.peer.value)
        assertEquals(PairedFingerprintState.MATCH, peer.state)
        assertEquals(PairedDeviceReasons.FINGERPRINT_MATCH, peer.reason)
        assertEquals(peerFingerprint.hex, peer.pinnedFingerprint)
        assertTrue(peer.canRegister)

        assertTrue(controller.registerPeer())
        val outcome = recorded(controller.peer.value)
        assertTrue(outcome.accepted)
        assertFalse(outcome.added)
        assertEquals(PairedDeviceReasons.UPDATED, outcome.reason)
    }

    @Test
    fun aKnownDeviceWithADifferentFingerprintIsNeverOverwritten() = runTest {
        val store = InMemoryPairedDeviceStore()
        val registry = registryAt(store)
        val pinnedBefore = PairedDevice.create(
            deviceId = "windows-a",
            fingerprint = selfFingerprint.hex,
            displayName = "客厅 Windows",
            nowEpochMs = issuedAt - 60_000L,
        )
        store.save(pinnedBefore)
        val writesBefore = store.writeCount
        val controller = controllerAt(issuedAt, cardPort(card()), registry)
        assertTrue(controller.verify(PairingLink.create(payload())))
        assertTrue(controller.fetchInvitation())

        val peer = reviewed(controller.peer.value)
        assertEquals(PairedFingerprintState.MISMATCH, peer.state)
        assertEquals(PairedDeviceReasons.FINGERPRINT_MISMATCH, peer.reason)
        assertEquals(selfFingerprint.hex, peer.pinnedFingerprint)
        assertFalse(peer.canRegister)

        assertFalse(controller.registerPeer())
        val outcome = recorded(controller.peer.value)
        assertFalse(outcome.accepted)
        assertFalse(outcome.added)
        assertEquals(PairedDeviceReasons.FINGERPRINT_MISMATCH, outcome.reason)
        // 已钉住的指纹绝不能因为一次不一致的配对被覆盖。
        val stillPinned = registry.find("windows-a") ?: error("记录不能凭空消失")
        assertEquals(pinnedBefore, stillPinned)
        assertEquals(writesBefore, store.writeCount)
    }

    @Test
    fun anInvitationWithoutACardIsNoCardRatherThanAFailure() = runTest {
        val store = InMemoryPairedDeviceStore()
        val controller = controllerAt(issuedAt, cardPort(null), registryAt(store))
        assertTrue(controller.verify(PairingLink.create(payload())))

        assertTrue(controller.fetchInvitation())

        assertSame(PairingPeerState.NoCard, controller.peer.value)
        assertEquals(PairingPeerCopy.NO_CARD_HINT, PairingPeerCopy.lineOf(controller.peer.value))
        assertNull(PairingPeerCopy.codeOf(controller.peer.value))
        assertFalse(controller.registerPeer())
        assertSame(PairingPeerState.NoCard, controller.peer.value)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun withoutARegistryThePeerBlockIsUnavailableAndNothingIsPinned() = runTest {
        val port = cardPort(card())
        val controller = controllerAt(issuedAt, port)
        assertTrue(controller.verify(PairingLink.create(payload())))

        assertTrue(controller.fetchInvitation())

        assertSame(PairingPeerState.Unavailable, controller.peer.value)
        assertEquals(PairingLinkController.REGISTRY_UNAVAILABLE, PairingPeerCopy.codeOf(controller.peer.value))
        assertFalse(controller.registerPeer())
        assertSame(PairingPeerState.Unavailable, controller.peer.value)
        // 没有登记簿的一方不自报身份（与 Windows 同一条边界）。
        assertTrue(port.selfCardSeen)
        assertNull(port.selfCard)
    }

    @Test
    fun aPortWithoutCardSupportDegradesToNoCard() = runTest {
        val registry = registryAt()
        val port = PlainFetchPort { givenChallenge, givenFingerprint ->
            val given = PairingPayload.create(
                deviceId = "windows-a",
                ephemeralUrl = "https://192.168.1.20:8443/pairing",
                challenge = givenChallenge,
                publicKeyFingerprint = givenFingerprint,
                nowEpochMs = issuedAt,
            )
            PairingInvitationResult.Ok(given, PairingPayloadCodec.encode(given))
        }
        val controller = controllerAt(issuedAt, port, registry, card())
        assertTrue(controller.verify(PairingLink.create(payload())))

        assertTrue(controller.fetchInvitation())

        assertSame(PairingPeerState.NoCard, controller.peer.value)
    }

    @Test
    fun theSelfCardIsSentOnlyWhenARegistryExists() = runTest {
        val self = card(deviceId = "android-1", displayName = "Android", fingerprint = selfFingerprint)
        val withRegistry = cardPort(card())
        val first = controllerAt(issuedAt, withRegistry, registryAt(), self)
        assertTrue(first.verify(PairingLink.create(payload())))
        assertTrue(first.fetchInvitation())
        assertSame(self, withRegistry.selfCard)

        val withoutRegistry = cardPort(card())
        val second = controllerAt(issuedAt, withoutRegistry, null, self)
        assertTrue(second.verify(PairingLink.create(payload())))
        assertTrue(second.fetchInvitation())
        assertNull(withoutRegistry.selfCard)
    }

    @Test
    fun aFailedFetchDropsThePreviousPeerConclusion() = runTest {
        var fail = false
        val port = CardFetchPort {
            if (fail) {
                PairingCardExchangeResult.Failed("peer-timeout", "对端没有在预算内回卡片")
            } else {
                PairingCardExchangeResult.Ok(payload(), PairingPayloadCodec.encode(payload()), card())
            }
        }
        val controller = controllerAt(issuedAt, port, registryAt())
        assertTrue(controller.verify(PairingLink.create(payload())))
        assertTrue(controller.fetchInvitation())
        assertTrue(controller.peer.value is PairingPeerState.Reviewed)

        fail = true
        assertFalse(controller.fetchInvitation())

        assertSame(PairingPeerState.NotChecked, controller.peer.value)
        assertNull(PairingPeerCopy.lineOf(controller.peer.value))
    }

    @Test
    fun changingTheLinkDropsThePreviousPeerConclusion() = runTest {
        val controller = controllerAt(issuedAt, cardPort(card()), registryAt())
        assertTrue(controller.verify(PairingLink.create(payload())))
        assertTrue(controller.fetchInvitation())
        assertTrue(controller.peer.value is PairingPeerState.Reviewed)

        assertTrue(controller.verify(PairingLink.create(payload(deviceId = "windows-b"))))

        assertSame(PairingPeerState.NotChecked, controller.peer.value)
    }

    @Test
    fun thePeerCopyNeverLeaksTheChallengeOrTheRawLink() = runTest {
        val controller = controllerAt(issuedAt, cardPort(card()), registryAt())
        val link = PairingLink.create(payload())
        assertTrue(controller.verify(link))
        assertTrue(controller.fetchInvitation())

        val lines = listOfNotNull(
            PairingPeerCopy.lineOf(controller.peer.value),
            PairingPeerCopy.lineOf(PairingPeerState.Recorded("windows-a", "客厅 Windows", peerFingerprint.hex, PairedFingerprintState.NEW, PairedDeviceReasons.REGISTERED, true, true)),
        )
        for (line in lines) {
            assertFalse(line.contains(challenge.base64))
            assertFalse(line.contains(challenge.toString()))
            assertFalse(line.contains("mediahub://"))
            assertFalse(line.contains(link))
        }
        assertTrue(lines[0].contains("windows-a"))
        assertTrue(lines[0].contains(peerFingerprint.hex))
    }

    @Test
    fun aMismatchLineCarriesTheNeverOverwriteNote() = runTest {
        val store = InMemoryPairedDeviceStore()
        store.save(
            PairedDevice.create(
                deviceId = "windows-a",
                fingerprint = selfFingerprint.hex,
                displayName = "",
                nowEpochMs = issuedAt - 60_000L,
            ),
        )
        val controller = controllerAt(issuedAt, cardPort(card()), registryAt(store))
        assertTrue(controller.verify(PairingLink.create(payload())))
        assertTrue(controller.fetchInvitation())

        val line = PairingPeerCopy.lineOf(controller.peer.value) ?: error("Mismatch 必须有结果行")

        assertTrue(line.contains(PairingPeerCopy.MISMATCH_NOTE))
        assertEquals(PairedDeviceReasons.FINGERPRINT_MISMATCH, PairingPeerCopy.codeOf(controller.peer.value))
    }

    @Test
    fun aCardWithoutADisplayNameFallsBackToTheDeviceId() = runTest {
        val controller = controllerAt(issuedAt, cardPort(card(displayName = "   ")), registryAt())
        assertTrue(controller.verify(PairingLink.create(payload())))
        assertTrue(controller.fetchInvitation())

        assertEquals("windows-a", reviewed(controller.peer.value).displayName)
    }

    @Test
    fun thePeerCopyIsStableAndSaysWhatRegisteringDoesNotDo() {
        assertEquals("对端身份", PairingPeerCopy.TITLE)
        assertEquals("登记这台设备", PairingPeerCopy.REGISTER_ACTION)
        assertTrue(PairingPeerCopy.REGISTER_HINT.contains("不写任何凭据"))
        assertTrue(PairingPeerCopy.REGISTER_HINT.contains("不建立同步"))
        assertTrue(PairingPeerCopy.HINT.contains("稳定指纹"))
        assertTrue(PairingPeerCopy.MISMATCH_NOTE.contains("不会覆盖"))
    }

}
