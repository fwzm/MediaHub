package com.mediahub.feature.settings.sync

import com.mediahub.model.ServerType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-H 逐源验证协调器的 JVM 单测。
 *
 * 钉住的规则（对应 SourceVerificationCoordinator 的类注释 1..5）：
 * - 没有已存凭据 → NEEDS_CREDENTIALS 且零认证请求；
 * - 只有认证通过才落盘；失败/不支持/异常/取消各自不落盘；
 * - 密码与账号不进日志、不进文案（message 先脱敏）；
 * - 原因码来自封闭集合；
 * - 验证不改来源身份（快照只读，outcome.serverId 与入参一致）。
 *
 * 这里用假端口而不是 Robolectric：协调器不碰 Android API，纯逻辑必须能在 JVM 上秒级跑完。
 */
class SourceVerificationCoordinatorTest {

    @Test
    fun `a source without a stored secret needs credentials and sends no request`() = runTest {
        val credentials = FakeCredentialPort()
        val authenticator = FakeAuthenticator()
        val outcome = coordinator(credentials, authenticator).inspect(source())

        assertEquals(SourceVerificationState.NEEDS_CREDENTIALS, outcome.state)
        assertEquals(SourceVerificationReasons.NO_CREDENTIALS, outcome.reason)
        assertNull(outcome.checkedAtEpochMs)
        assertTrue(outcome.needsCredentials)
        assertTrue("认证接口绝不能被调用", authenticator.calls.isEmpty())
        assertEquals(0, credentials.puts)
    }

    @Test
    fun `blank typed secret falls back to inspect and writes nothing`() = runTest {
        val credentials = FakeCredentialPort()
        val authenticator = FakeAuthenticator()
        val coordinator = coordinator(credentials, authenticator)

        val outcome = coordinator.verify(source(), "   ")

        assertEquals(SourceVerificationState.NEEDS_CREDENTIALS, outcome.state)
        assertTrue(authenticator.calls.isEmpty())
        assertEquals(0, credentials.puts)
    }

    @Test
    fun `a stored secret is really used to authenticate`() = runTest {
        val credentials = FakeCredentialPort()
        credentials.put("srv-1", "stored-pw")
        val authenticator = FakeAuthenticator()

        val outcome = coordinator(credentials, authenticator).inspect(source())

        assertEquals(SourceVerificationState.VERIFIED, outcome.state)
        assertEquals(SourceVerificationReasons.VERIFIED, outcome.reason)
        assertEquals(1, authenticator.calls.size)
        assertEquals("stored-pw", authenticator.calls.single().third)
        assertEquals("alice", authenticator.calls.single().second)
        assertEquals("inspect 只读，不得落盘", 0, credentials.puts)
    }

    @Test
    fun `a rejected password is auth-failed and writes nothing`() = runTest {
        val credentials = FakeCredentialPort()
        val authenticator = FakeAuthenticator(SourceAuthVerdict(verified = false))

        val outcome = coordinator(credentials, authenticator).verify(source(), "wrong-pw")

        assertEquals(SourceVerificationState.FAILED, outcome.state)
        assertEquals(SourceVerificationReasons.AUTH_FAILED, outcome.reason)
        assertTrue(outcome.isFailed)
        assertEquals("失败不得留下凭据", 0, credentials.puts)
        assertNull(credentials.secretOf("srv-1"))
    }

    @Test
    fun `an accepted password is persisted exactly once`() = runTest {
        val credentials = FakeCredentialPort()

        val outcome = coordinator(credentials, FakeAuthenticator()).verify(source(), "good-pw")

        assertEquals(SourceVerificationState.VERIFIED, outcome.state)
        assertEquals(1, credentials.puts)
        assertEquals("good-pw", credentials.secretOf("srv-1"))
        assertTrue(outcome.isVerified)
    }

    @Test
    fun `verifying twice with the same password does not rewrite the credential`() = runTest {
        val credentials = FakeCredentialPort()
        val coordinator = coordinator(credentials, FakeAuthenticator())

        coordinator.verify(source(), "good-pw")
        val second = coordinator.verify(source(), "good-pw")

        assertEquals(SourceVerificationState.VERIFIED, second.state)
        assertEquals("重复验证必须幂等：相同值不重复写", 1, credentials.puts)
    }

    @Test
    fun `an authenticator exception becomes auth-unavailable instead of crashing`() = runTest {
        val credentials = FakeCredentialPort()
        val authenticator = FakeAuthenticator(failure = IllegalStateException("socket closed"))

        val outcome = coordinator(credentials, authenticator).verify(source(), "good-pw")

        assertEquals(SourceVerificationState.FAILED, outcome.state)
        assertEquals(SourceVerificationReasons.AUTH_UNAVAILABLE, outcome.reason)
        assertEquals(SourceVerificationCopy.UNAVAILABLE_MESSAGE, outcome.message)
        assertEquals(0, credentials.puts)
    }

    @Test
    fun `an unsupported source type is reported without persisting anything`() = runTest {
        val credentials = FakeCredentialPort()
        val verdict = SourceAuthVerdict(
            verified = false,
            reason = SourceVerificationReasons.AUTH_NOT_SUPPORTED,
            message = SourceVerificationCopy.NOT_SUPPORTED_MESSAGE,
        )
        val source = source(serverType = ServerType.SMB)

        val outcome = coordinator(credentials, FakeAuthenticator(verdict)).verify(source, "x-pw-1234")

        assertEquals(SourceVerificationState.FAILED, outcome.state)
        assertEquals(SourceVerificationReasons.AUTH_NOT_SUPPORTED, outcome.reason)
        assertEquals(0, credentials.puts)
    }

    @Test
    fun `a failing credential write is reported as credentials-save-failed`() = runTest {
        val credentials = FakeCredentialPort(saveFailure = IllegalStateException("keystore busy"))

        val outcome = coordinator(credentials, FakeAuthenticator()).verify(source(), "good-pw")

        assertEquals(SourceVerificationState.FAILED, outcome.state)
        assertEquals(SourceVerificationReasons.CREDENTIALS_SAVE_FAILED, outcome.reason)
        assertEquals(SourceVerificationCopy.SAVE_FAILED_MESSAGE, outcome.message)
        assertEquals("写失败就等于没补录成功", 0, credentials.puts)
    }

    @Test
    fun `an unreadable credential store is reported as credentials-unavailable`() = runTest {
        val credentials = FakeCredentialPort(readFailure = IllegalStateException("keystore dead"))
        val authenticator = FakeAuthenticator()

        val outcome = coordinator(credentials, authenticator).inspect(source())

        assertEquals(SourceVerificationState.FAILED, outcome.state)
        assertEquals(SourceVerificationReasons.CREDENTIALS_UNAVAILABLE, outcome.reason)
        assertEquals(SourceVerificationCopy.CREDENTIALS_UNAVAILABLE_MESSAGE, outcome.message)
        assertTrue("读不到凭据就不该发请求", authenticator.calls.isEmpty())
    }

    @Test
    fun `auth messages are sanitised so the password never reaches the ui`() = runTest {
        val credentials = FakeCredentialPort()
        val verdict = SourceAuthVerdict(
            verified = false,
            reason = SourceVerificationReasons.AUTH_FAILED,
            message = "login failed for alice with secret s3cret-Pa55word",
        )

        val outcome = coordinator(credentials, FakeAuthenticator(verdict))
            .verify(source(), "s3cret-Pa55word")

        assertFalse("密码不得出现在文案里", outcome.message.contains("s3cret-Pa55word"))
        assertFalse("账号也不得出现在文案里", outcome.message.contains("alice"))
        assertTrue(outcome.message.contains(SourceVerificationCopy.MASK))
    }

    @Test
    fun `logs carry the state and reason code but no password`() = runTest {
        val credentials = FakeCredentialPort()
        val log = RecordingLog()
        val coordinator = coordinator(credentials, FakeAuthenticator(), log = log)

        coordinator.verify(source(), "s3cret-Pa55word")
        coordinator.inspect(source())

        assertEquals(2, log.lines.size)
        log.lines.forEach { line ->
            assertFalse("日志不得含密码：" + line, line.contains("s3cret-Pa55word"))
            assertFalse("日志不得含账号：" + line, line.contains("alice"))
            assertTrue(line.startsWith("source-verify serverId="))
        }
        assertTrue(log.lines.first().contains("state=VERIFIED"))
        assertTrue(log.lines.first().contains("reason=" + SourceVerificationReasons.VERIFIED))
    }

    @Test
    fun `verification never changes the source identity`() = runTest {
        val credentials = FakeCredentialPort()
        val original = source()
        val snapshot = original.copy()

        val outcome = coordinator(credentials, FakeAuthenticator()).verify(original, "good-pw")

        assertEquals("快照必须是只读的", snapshot, original)
        assertEquals(original.serverId, outcome.serverId)
        assertEquals("local-1", original.syncSourceId)
    }

    @Test
    fun `inspect returns one outcome per source in input order`() = runTest {
        val credentials = FakeCredentialPort()
        credentials.put("srv-2", "b-pw")
        val sources = listOf(
            source(id = "srv-1", title = "客厅 Emby"),
            source(id = "srv-2", title = "书房 Jellyfin"),
            source(id = "srv-3", title = "NAS WebDAV"),
        )

        val outcomes = coordinator(credentials, FakeAuthenticator()).inspect(sources)

        assertEquals(3, outcomes.size)
        assertEquals(listOf("srv-1", "srv-2", "srv-3"), outcomes.map { it.serverId })
        assertEquals(SourceVerificationState.NEEDS_CREDENTIALS, outcomes[0].state)
        assertEquals(SourceVerificationState.VERIFIED, outcomes[1].state)
        assertEquals(SourceVerificationState.NEEDS_CREDENTIALS, outcomes[2].state)
    }

    @Test
    fun `inspect only authenticates the sources that have credentials`() = runTest {
        val credentials = FakeCredentialPort()
        credentials.put("srv-2", "b-pw")
        val authenticator = FakeAuthenticator()
        val sources = listOf(source(id = "srv-1"), source(id = "srv-2"), source(id = "srv-3"))

        coordinator(credentials, authenticator).inspect(sources)

        assertEquals(1, authenticator.calls.size)
        assertEquals("srv-2", authenticator.calls.single().first)
    }

    @Test
    fun `inspecting an empty list returns no outcomes and no calls`() = runTest {
        val authenticator = FakeAuthenticator()
        val outcomes = coordinator(FakeCredentialPort(), authenticator).inspect(emptyList())

        assertTrue(outcomes.isEmpty())
        assertTrue(authenticator.calls.isEmpty())
    }

    @Test
    fun `cancellation is never swallowed`() = runTest {
        val credentials = FakeCredentialPort()
        val authenticator = FakeAuthenticator(failure = CancellationException("cancelled"))

        val failure = runCatching {
            coordinator(credentials, authenticator).verify(source(), "good-pw")
        }.exceptionOrNull()

        assertTrue("协调器必须原样抛出取消", failure is CancellationException)
        assertEquals(0, credentials.puts)
    }

    @Test
    fun `an unknown verdict reason is normalised into auth-failed`() = runTest {
        val credentials = FakeCredentialPort()
        val verdict = SourceAuthVerdict(verified = false, reason = "made-up-code")

        val outcome = coordinator(credentials, FakeAuthenticator(verdict)).verify(source(), "pw-12345678")

        assertEquals(SourceVerificationReasons.AUTH_FAILED, outcome.reason)
        assertTrue(SourceVerificationReasons.ALL.contains(outcome.reason))
    }

    @Test
    fun `every reported reason stays inside the closed set`() = runTest {
        val verifiedOutcomes = listOf(
            coordinator(FakeCredentialPort(), FakeAuthenticator()).inspect(source()),
            coordinator(FakeCredentialPort(), FakeAuthenticator()).verify(source(), "good-pw"),
            coordinator(FakeCredentialPort(), FakeAuthenticator(SourceAuthVerdict(false)))
                .verify(source(), "bad-pw"),
            coordinator(
                FakeCredentialPort(saveFailure = IllegalStateException("busy")),
                FakeAuthenticator(),
            ).verify(source(), "good-pw"),
        )

        verifiedOutcomes.forEach { outcome ->
            assertTrue(
                "非法原因码：" + outcome.reason,
                SourceVerificationReasons.ALL.contains(outcome.reason),
            )
            assertTrue(outcome.message.isNotBlank())
        }
    }

    @Test
    fun `the three states render three different status lines`() {
        val needs = SourceVerificationCopy.stateText(
            SourceVerificationState.NEEDS_CREDENTIALS,
            SourceVerificationReasons.NO_CREDENTIALS,
        )
        val verified = SourceVerificationCopy.stateText(
            SourceVerificationState.VERIFIED,
            SourceVerificationReasons.VERIFIED,
        )
        val failed = SourceVerificationCopy.stateText(
            SourceVerificationState.FAILED,
            SourceVerificationReasons.AUTH_FAILED,
        )

        assertEquals(SourceVerificationCopy.NEEDS_CREDENTIALS_TEXT, needs)
        assertEquals(SourceVerificationCopy.VERIFIED_TEXT, verified)
        assertTrue(failed.startsWith(SourceVerificationCopy.FAILED_PREFIX))
        assertEquals("三态文案必须互不相同", 3, setOf(needs, verified, failed).size)
    }

    @Test
    fun `the view state keeps verified rows when the source list is refreshed`() {
        val sources = listOf(source(id = "srv-1"), source(id = "srv-2"))
        val verified = SourceVerificationOutcome(
            serverId = "srv-1",
            state = SourceVerificationState.VERIFIED,
            reason = SourceVerificationReasons.VERIFIED,
            message = SourceVerificationCopy.VERIFIED_MESSAGE,
            checkedAtEpochMs = 1_760_000_000_000L,
        )
        val state = SourceVerificationViewState(sources = sources).withOutcome(verified)

        val refreshed = state.withSources(sources)

        assertEquals(SourceVerificationState.VERIFIED, refreshed.stateOf("srv-1"))
        assertEquals(SourceVerificationCopy.VERIFIED_TEXT, refreshed.lineFor("srv-1"))
        assertEquals(
            SourceVerificationCopy.NEEDS_CREDENTIALS_TEXT,
            refreshed.lineFor("srv-2"),
        )
        val trimmed = refreshed.withSources(listOf(source(id = "srv-2")))
        assertNull("消失的来源必须带走它的结论", trimmed.outcomeFor("srv-1"))
    }

    @Test
    fun `the dialog only opens for a source that is still present`() {
        val state = SourceVerificationViewState(sources = listOf(source(id = "srv-1")))
            .withDialog("srv-1")

        assertTrue(state.isDialogOpen)
        assertEquals("srv-1", state.dialogSource?.serverId)
        val gone = state.withSources(listOf(source(id = "srv-2")))
        assertFalse(gone.isDialogOpen)
        assertNull(gone.dialogSource)
    }

    private fun coordinator(
        credentials: SourceCredentialPort,
        authenticator: SourceAuthenticator,
        log: SourceVerificationLog = RecordingLog(),
    ): SourceVerificationCoordinator = SourceVerificationCoordinator(
        credentials = credentials,
        authenticator = authenticator,
        log = log,
        clock = { 1_760_000_000_000L },
    )

    private fun source(
        id: String = "srv-1",
        title: String = "客厅 Emby",
        serverType: ServerType = ServerType.EMBY,
    ): SourceVerificationSource = SourceVerificationSource(
        serverId = id,
        title = title,
        serverType = serverType,
        accountScope = "alice",
        syncSourceId = "local-1",
    )
}

/** 凭据端口的测试替身：只记次数，不碰 Android Keystore。 */
private class FakeCredentialPort(
    private val stored: MutableMap<String, String> = mutableMapOf(),
    private val readFailure: Exception? = null,
    private val saveFailure: Exception? = null,
) : SourceCredentialPort {

    var puts: Int = 0
        private set

    override suspend fun hasSecret(serverId: String): Boolean = stored.containsKey(serverId)

    override suspend fun readSecret(serverId: String): String? {
        readFailure?.let { throw it }
        return stored[serverId]
    }

    override suspend fun saveSecret(serverId: String, secret: String) {
        saveFailure?.let { throw it }
        puts += 1
        stored[serverId] = secret
    }

    fun put(serverId: String, secret: String) {
        stored[serverId] = secret
    }

    fun secretOf(serverId: String): String? = stored[serverId]
}

/** 校验端口的测试替身：记录每次调用的 serverId/账号/密码。 */
private class FakeAuthenticator(
    private val verdict: SourceAuthVerdict = SourceAuthVerdict(verified = true),
    private val failure: Exception? = null,
) : SourceAuthenticator {

    val calls: MutableList<Triple<String, String?, String>> = mutableListOf()

    override suspend fun authenticate(
        source: SourceVerificationSource,
        username: String?,
        secret: String,
    ): SourceAuthVerdict {
        calls += Triple(source.serverId, username, secret)
        failure?.let { throw it }
        return verdict
    }
}

/** 日志端口的测试替身：把每行原样留下来供断言。 */
private class RecordingLog : SourceVerificationLog {
    val lines: MutableList<String> = mutableListOf()

    override fun record(line: String) {
        lines += line
    }
}
