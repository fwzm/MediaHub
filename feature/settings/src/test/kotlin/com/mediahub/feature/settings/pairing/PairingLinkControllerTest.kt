package com.mediahub.feature.settings.pairing

import com.mediahub.core.common.pairing.PairingChallenge
import com.mediahub.core.common.pairing.PairingCode
import com.mediahub.core.common.pairing.PairingCodeReasons
import com.mediahub.core.common.pairing.PairingFingerprint
import com.mediahub.core.common.pairing.PairingLink
import com.mediahub.core.common.pairing.PairingLinkReasons
import com.mediahub.core.common.pairing.PairingPayload
import com.mediahub.core.common.pairing.PairingReasons
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-59：「配对链接核对」的 JVM 证据（纯 JVM，不开设备、不碰 Compose、不拨号）。
 *
 * 这一层要钉住的是三件事，而不是界面长什么样：
 * 1. 合法链接解析出的设备标识 / 公钥指纹 / 剩余秒数确实来自链接本身（用固定时钟算，不靠 sleep）；
 * 2. 非法链接原样透出 core:common 的稳定码，不自造 Android 专用码；
 * 3. 核对成功也不会泄漏挑战，且失败会把上一次成功结论一起作废（不留半成品）。
 *
 * 链接全部用 [PairingLink.create] 现场造，与 Windows 的黄金向量同格式，不写死 base64。
 */
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

    private fun controllerAt(nowEpochMs: Long) = PairingLinkController { nowEpochMs }

    private fun failed(state: PairingLinkState): PairingLinkState.Failed {
        assertTrue("期望失败态，实际 " + state, state is PairingLinkState.Failed)
        return state as PairingLinkState.Failed
    }

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
    fun theCopyIsStableAndSaysTheVersionDoesNotConnect() {
        assertEquals("配对设备", PairingLinkCopy.TITLE)
        assertEquals("核对链接", PairingLinkCopy.ACTION)
        assertEquals("配对链接", PairingLinkCopy.LINK_LABEL)
        assertTrue(PairingLinkCopy.NOT_YET.contains("不会连接对端"))
        assertNotEquals(PairingLinkCopy.HINT, PairingLinkCopy.NOT_YET)
    }
}
