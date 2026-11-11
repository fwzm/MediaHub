package com.mediahub.feature.settings.pairing

import com.mediahub.core.common.pairing.PairingChallenge
import com.mediahub.core.common.pairing.PairingCode
import com.mediahub.core.common.pairing.PairingCodeReasons
import com.mediahub.core.common.pairing.PairingFingerprint
import com.mediahub.core.common.pairing.PairingInvitationResult
import com.mediahub.core.common.pairing.PairingLink
import com.mediahub.core.common.pairing.PairingLinkReasons
import com.mediahub.core.common.pairing.PairingPayload
import com.mediahub.core.common.pairing.PairingPayloadCodec
import com.mediahub.core.common.pairing.PairingReasons
import com.mediahub.core.common.pairing.PairingTransportReasons
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-59 / W6-59b：「核对链接 + 拉一次邀请」的 JVM 证据（纯 JVM，不开设备、不碰 Compose）。
 *
 * 这一层要钉住的是四件事，而不是界面长什么样：
 * 1. 合法链接解析出的设备标识 / 公钥指纹 / 剩余秒数确实来自链接本身（用固定时钟算，不靠 sleep）；
 * 2. 非法链接原样透出 core:common 的稳定码，不自造 Android 专用码；
 * 3. 核对成功也不会泄漏挑战，且失败会把上一次成功结论一起作废（不留半成品）；
 * 4. 拉邀请这一跳（W6-59b）把链接里的地址、挑战与**那一枚**指纹原样交给出口，失败码原样透出，
 *    单飞期间再来一次不拨第二次。
 *
 * 链接全部用 [PairingLink.create] 现场造，与 Windows 的黄金向量同格式，不写死 base64。
 * 拉邀请一律走替身 [PairingFetchPort]：本类不拨号、不建 socket。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingLinkControllerTest {

    private val issuedAt: Long = 1_760_000_000_000L

    private val challenge: PairingChallenge = PairingChallenge.createRandom()

    private val fingerprint: PairingFingerprint =
        PairingFingerprint.fromBytes(ByteArray(PairingFingerprint.BYTE_LENGTH) { index -> (index + 7).toByte() })

    private fun payload(
        deviceId: String = "windows-a",
        ephemeralUrl: String = "https://192.168.1.20:8443/pairing",
        nowEpochMs: Long = issuedAt,
    ): PairingPayload = PairingPayload.create(
        deviceId = deviceId,
        ephemeralUrl = ephemeralUrl,
        challenge = challenge,
        publicKeyFingerprint = fingerprint,
        nowEpochMs = nowEpochMs,
    )

    private fun link(
        deviceId: String = "windows-a",
        ephemeralUrl: String = "https://192.168.1.20:8443/pairing",
        nowEpochMs: Long = issuedAt,
    ): String = PairingLink.create(payload(deviceId, ephemeralUrl, nowEpochMs))

    private fun controllerAt(nowEpochMs: Long, port: PairingFetchPort? = null) =
        PairingLinkController(now = { nowEpochMs }, fetchPort = port)

    private fun failed(state: PairingLinkState): PairingLinkState.Failed {
        assertTrue("期望失败态，实际 " + state, state is PairingLinkState.Failed)
        return state as PairingLinkState.Failed
    }

    /** 拉邀请的替身：记录参数、可选地挂住（模拟一次在飞的拨号），再回给定的结果。 */
    private class FakeFetchPort(
        private val answer: (PairingPayload) -> PairingInvitationResult,
    ) : PairingFetchPort {

        var calls: Int = 0

        var host: String? = null

        var port: Int? = null

        var challenge: PairingChallenge? = null

        var fingerprint: PairingFingerprint? = null

        var gate: CompletableDeferred<Unit>? = null

        private var lastPayload: PairingPayload? = null

        override suspend fun fetch(
            host: String,
            port: Int,
            expectedChallenge: PairingChallenge,
            expectedFingerprint: PairingFingerprint,
        ): PairingInvitationResult {
            calls += 1
            this.host = host
            this.port = port
            this.challenge = expectedChallenge
            this.fingerprint = expectedFingerprint
            gate?.await()
            val payload = lastPayload ?: error("测试没有提供载荷")
            return answer(payload)
        }

        fun answerWith(payload: PairingPayload) {
            lastPayload = payload
        }
    }

    private fun fetchPort(
        payload: PairingPayload = payload(),
        answer: (PairingPayload) -> PairingInvitationResult = { given ->
            PairingInvitationResult.Ok(given, invitationJson(given))
        },
    ): FakeFetchPort = FakeFetchPort(answer).also { it.answerWith(payload) }

    /** 对端回的 JSON 文本；控制器不解析它（校验在 core 里），这里只要有内容即可。 */
    private fun invitationJson(payload: PairingPayload): String = PairingPayloadCodec.encode(payload)

    @Test
    fun aRealLinkYieldsTheDeviceFingerprintEndpointAndRemainingSeconds() {
        val controller = controllerAt(issuedAt + 30_000L)

        assertTrue(controller.verify(link()))

        val parsed = controller.state.value as PairingLinkState.Parsed
        assertEquals("windows-a", parsed.deviceId)
        assertEquals(fingerprint.displayString, parsed.fingerprint)
        assertEquals("192.168.1.20:8443", parsed.endpoint)
        assertEquals(90L, parsed.remainingSeconds)

        val line = PairingLinkCopy.lineOf(controller.state.value) ?: error("成功态必须有结果行")
        assertTrue(line.contains("windows-a"))
        assertTrue(line.contains(fingerprint.displayString))
        assertTrue(line.contains("90 秒"))
        assertNull(PairingLinkCopy.codeOf(controller.state.value))
    }

    @Test
    fun anHttpsLinkWithoutAPortFallsBackToFourFortyThree() {
        val controller = controllerAt(issuedAt)

        assertTrue(controller.verify(link(ephemeralUrl = "https://192.168.1.20/pairing")))

        assertEquals("192.168.1.20:443", (controller.state.value as PairingLinkState.Parsed).endpoint)
    }

    @Test
    fun aBlankLinkIsRefusedWithTheContractEmptyCode() {
        val controller = controllerAt(issuedAt)

        assertFalse(controller.verify("   "))

        // 空链接由契约层判 pairing-link-empty（不是 code-empty：那是「有链接但码为空」的码）。
        val state = failed(controller.state.value)
        assertEquals(PairingLinkReasons.EMPTY, state.reason)
        assertEquals(PairingLinkCopy.EMPTY_HINT, state.message)
        assertEquals(PairingLinkReasons.EMPTY, PairingLinkCopy.codeOf(controller.state.value))
    }

    @Test
    fun aBareCodeWithoutTheLinkPrefixIsNotAPairingLink() {
        val controller = controllerAt(issuedAt)

        assertFalse(controller.verify(PairingCode.encode(payload())))

        assertEquals(PairingLinkReasons.NOT_A_LINK, failed(controller.state.value).reason)
    }

    @Test
    fun anExpiredLinkIsRefused() {
        val controller = controllerAt(issuedAt + PairingPayload.LIFETIME_MILLISECONDS + 1L)

        assertFalse(controller.verify(link()))

        assertEquals(PairingReasons.EXPIRED, failed(controller.state.value).reason)
    }

    @Test
    fun anOverLongLinkIsRefusedByLength() {
        val controller = controllerAt(issuedAt)
        val tooLong = PairingLink.PREFIX + "A".repeat(PairingLink.MAXIMUM_LENGTH)

        assertFalse(controller.verify(tooLong))

        assertEquals(PairingLinkReasons.TOO_LONG, failed(controller.state.value).reason)
    }

    @Test
    fun textThatIsNotBase64UrlIsRefused() {
        val controller = controllerAt(issuedAt)

        assertFalse(controller.verify(PairingLink.PREFIX + "!!!!!!!!"))

        assertEquals(PairingCodeReasons.NOT_BASE64URL, failed(controller.state.value).reason)
    }

    @Test
    fun aPayloadThatIsNotAPairingPayloadIsPayloadInvalid() {
        val controller = controllerAt(issuedAt)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray(Charsets.UTF_8))

        assertFalse(controller.verify(PairingLink.PREFIX + encoded))

        assertEquals(PairingCodeReasons.PAYLOAD_INVALID, failed(controller.state.value).reason)
    }

    @Test
    fun aFailedVerifyAfterASuccessDropsThePreviousConclusion() {
        val controller = controllerAt(issuedAt)
        assertTrue(controller.verify(link()))

        assertFalse(controller.verify("not-a-link-at-all"))

        val state = failed(controller.state.value)
        assertEquals(PairingLinkReasons.NOT_A_LINK, state.reason)
        assertEquals(PairingLinkReasons.NOT_A_LINK, PairingLinkCopy.codeOf(state))
    }

    @Test
    fun clearReturnsToIdle() {
        val controller = controllerAt(issuedAt)
        assertTrue(controller.verify(link()))

        controller.clear()

        assertEquals(PairingLinkState.Idle, controller.state.value)
        assertNull(PairingLinkCopy.lineOf(controller.state.value))
        assertNull(PairingLinkCopy.codeOf(controller.state.value))
    }

    @Test
    fun theSuccessLineLeaksNeitherTheChallengeNorTheRawLink() {
        val controller = controllerAt(issuedAt)
        assertTrue(controller.verify(link()))

        val line = PairingLinkCopy.lineOf(controller.state.value) ?: error("成功态必须有结果行")

        assertFalse(line.contains(challenge.base64))
        assertFalse(line.contains("mediahub://"))
        assertFalse(line.contains(challenge.toString()))
    }

    @Test
    fun theFailureLineCarriesTheStableCode() {
        val controller = controllerAt(issuedAt)
        assertFalse(controller.verify(""))

        val line = PairingLinkCopy.lineOf(controller.state.value) ?: error("失败态必须有结果行")

        assertTrue(line.contains(PairingLinkReasons.EMPTY))
        assertTrue(line.contains(PairingLinkCopy.EMPTY_HINT))
    }

    @Test
    fun theCopyIsStableAndSaysTheVersionOnlyVerifiesAndFetches() {
        assertEquals("配对设备", PairingLinkCopy.TITLE)
        assertEquals("核对链接", PairingLinkCopy.ACTION)
        assertEquals("拉取邀请", PairingLinkCopy.FETCH_ACTION)
        assertEquals("配对链接", PairingLinkCopy.LINK_LABEL)
        assertTrue(PairingLinkCopy.NOT_YET.contains("不写任何凭据"))
        assertTrue(PairingLinkCopy.NOT_YET.contains("不建立同步"))
        assertTrue(PairingLinkCopy.FETCH_HINT.contains("fingerprint-mismatch"))
        assertTrue(PairingLinkCopy.FETCH_HINT.contains("challenge-mismatch"))
        assertTrue(PairingLinkCopy.FETCH_HINT.contains("lifetime-exceeded"))
        assertNotEquals(PairingLinkCopy.HINT, PairingLinkCopy.NOT_YET)
    }

    @Test
    fun fetchingWithoutAVerifiedLinkIsRefusedBeforeAnyPort() = runTest {
        val port = fetchPort()
        val controller = controllerAt(issuedAt, port)

        assertFalse(controller.fetchInvitation())

        val state = failed(controller.state.value)
        assertEquals(PairingLinkController.PAIRING_NOT_VERIFIED, state.reason)
        assertEquals(PairingLinkCopy.NOT_VERIFIED_HINT, state.message)
        assertEquals(0, port.calls)
    }

    @Test
    fun fetchingWithoutAPortReportsTheUnavailableCode() = runTest {
        val controller = controllerAt(issuedAt)
        assertTrue(controller.verify(link()))

        assertFalse(controller.fetchInvitation())

        assertEquals(PairingLinkController.PAIRING_FETCH_UNAVAILABLE, failed(controller.state.value).reason)
    }

    @Test
    fun aSuccessfulFetchShowsTheValidatedInvitation() = runTest {
        val peer = payload(deviceId = "windows-b", nowEpochMs = issuedAt + 9_000L)
        val port = fetchPort(peer)
        val controller = controllerAt(issuedAt + 30_000L, port)
        assertTrue(controller.verify(link()))

        assertTrue(controller.fetchInvitation())

        val fetched = controller.state.value as PairingLinkState.Fetched
        assertEquals("windows-b", fetched.deviceId)
        assertEquals(fingerprint.displayString, fetched.fingerprint)
        assertEquals("192.168.1.20:8443", fetched.endpoint)
        // 对端 9000 ms 时签发 ⇒ 到期在链接签发 +129000 ms；本端时钟在 +30000 ms，故剩 99 秒。
        assertEquals(99L, fetched.remainingSeconds)
        assertEquals(1, port.calls)
        assertNull(PairingLinkCopy.codeOf(controller.state.value))
        val line = PairingLinkCopy.lineOf(controller.state.value) ?: error("拉成功必须有结果行")
        assertTrue(line.contains("邀请已校验"))
        assertTrue(line.contains("windows-b"))
    }

    @Test
    fun thePortGetsTheHostPortChallengeAndFingerprintFromTheLink() = runTest {
        val port = fetchPort()
        val controller = controllerAt(issuedAt, port)
        assertTrue(controller.verify(link(ephemeralUrl = "https://192.168.1.77:9443/pairing")))

        assertTrue(controller.fetchInvitation())

        assertEquals("192.168.1.77", port.host)
        assertEquals(9443, port.port)
        // 载荷是解析出来的新实例，比的是值不是引用（PairingChallenge / PairingFingerprint 都实现了 equals）。
        assertEquals(challenge, port.challenge)
        assertEquals(fingerprint, port.fingerprint)
    }

    @Test
    fun aFetchFailureKeepsTheCoreCodeVerbatim() = runTest {
        val port = fetchPort(answer = { _ ->
            PairingInvitationResult.Failed(PairingTransportReasons.FINGERPRINT_MISMATCH, "对端公钥指纹与链接不符")
        })
        val controller = controllerAt(issuedAt, port)
        assertTrue(controller.verify(link()))

        assertFalse(controller.fetchInvitation())

        val state = failed(controller.state.value)
        assertEquals(PairingTransportReasons.FINGERPRINT_MISMATCH, state.reason)
        assertEquals("对端公钥指纹与链接不符", state.message)
        assertEquals(PairingTransportReasons.FINGERPRINT_MISMATCH, PairingLinkCopy.codeOf(state))
        val line = PairingLinkCopy.lineOf(state) ?: error("失败态必须有结果行")
        assertTrue(line.contains(PairingTransportReasons.FINGERPRINT_MISMATCH))
    }

    @Test
    fun aSecondFetchWhileOneIsInFlightIsIgnored() = runTest {
        val port = fetchPort()
        val gate = CompletableDeferred<Unit>()
        port.gate = gate
        val controller = controllerAt(issuedAt, port)
        assertTrue(controller.verify(link()))

        val first = launch { controller.fetchInvitation() }
        advanceUntilIdle()
        assertTrue(controller.state.value is PairingLinkState.Fetching)

        assertFalse(controller.fetchInvitation())
        assertEquals(1, port.calls)

        gate.complete(Unit)
        advanceUntilIdle()
        first.join()
        assertTrue(controller.state.value is PairingLinkState.Fetched)
        assertEquals(1, port.calls)
    }

    @Test
    fun theFetchedLineLeaksNeitherTheChallengeNorTheRawLink() = runTest {
        val controller = controllerAt(issuedAt, fetchPort())
        assertTrue(controller.verify(link()))
        assertTrue(controller.fetchInvitation())

        val line = PairingLinkCopy.lineOf(controller.state.value) ?: error("拉成功必须有结果行")

        assertFalse(line.contains(challenge.base64))
        assertFalse(line.contains("mediahub://"))
        assertFalse(line.contains(challenge.toString()))
    }

    @Test
    fun clearAfterAFetchReturnsToIdle() = runTest {
        val controller = controllerAt(issuedAt, fetchPort())
        assertTrue(controller.verify(link()))
        assertTrue(controller.fetchInvitation())

        controller.clear()

        assertEquals(PairingLinkState.Idle, controller.state.value)
    }

    @Test
    fun aFetchAfterAFailedVerifyIsRefusedBecauseNothingIsHeld() = runTest {
        val port = fetchPort()
        val controller = controllerAt(issuedAt, port)
        assertFalse(controller.verify("not-a-link-at-all"))

        assertFalse(controller.fetchInvitation())

        assertEquals(PairingLinkController.PAIRING_NOT_VERIFIED, failed(controller.state.value).reason)
        assertEquals(0, port.calls)
    }
}
