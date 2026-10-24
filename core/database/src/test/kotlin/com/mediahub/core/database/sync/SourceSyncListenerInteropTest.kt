package com.mediahub.core.database.sync

import androidx.room.Room
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncEnvelope
import com.mediahub.core.common.sync.SourceSyncNonceCache
import com.mediahub.core.common.sync.SourceSyncReceiptOptions
import com.mediahub.core.common.sync.SourceSyncReceiptReasons
import com.mediahub.core.common.sync.SourceSyncTlsIdentity
import com.mediahub.core.common.sync.SourceSyncTlsOptions
import com.mediahub.core.common.sync.SourceSyncTransfer
import com.mediahub.core.common.sync.SourceSyncTransferAuthReasons
import com.mediahub.core.common.sync.SourceSyncTransferClient
import com.mediahub.core.common.sync.SourceSyncTransferOptions
import com.mediahub.core.common.sync.SourceSyncTransferReasons
import com.mediahub.core.common.sync.SourceSyncTransferResult
import com.mediahub.core.common.sync.SourceEndpoint
import com.mediahub.core.common.sync.SourceRecord
import com.mediahub.core.common.sync.SyncCapabilities
import com.mediahub.core.common.sync.SyncSourceTypeValue
import com.mediahub.core.database.AppDatabase
import com.mediahub.core.database.repository.ServerRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * W6-38：反向跨端联调 —— 真 Windows 的 `transfer send` 打进本端监听端（SourceSyncListenerImportService）。
 *
 * 与 W6-32（本端当发送方）互补：这里验的是「Windows 发出的真实字节，Android 监听端能收、能验、能落库」。
 *
 * 断言只认三类外部证据：
 * 1. 子进程退出码与 --json 里的字段（frames / bytes / sourceCount / certificateFingerprint / secretEnv）；
 * 2. Android 侧真实的 Room servers 表行（不是内存 fake）—— Android 没有 Windows 的 sync-sources.json，
 *    servers.syncRecordRevision 就是本端的基线；
 * 3. 监听端自己返回的稳定码（auth-mismatch / auth-replayed / tls-*）。
 *
 * 门禁：MH_W638_SYNC_DLL 指向 MediaHub.SyncCli.dll 才跑，否则整类 skipped（CI 上必须跳过，
 * 本套件刻意不进 android-ci.yml 的 --required-suite 名单）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceSyncListenerInteropTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ServerRepository
    private lateinit var service: AndroidSourceSyncService
    private val workspaces = ArrayList<File>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ServerRepository(db)
        service = AndroidSourceSyncService(
            store = repository,
            deviceId = ANDROID_DEVICE,
            snapshotIdFactory = { "snap-w638" },
            clock = { 1_792_800_000_000L },
        )
        assumeTrue(SKIP_MESSAGE, windowsCli() != null)
    }

    @After
    fun tearDown() {
        db.close()
        workspaces.forEach { workspace -> workspace.deleteRecursively() }
        workspaces.clear()
    }

    // ---------------------------------------------------------------- 用例

    @Test
    fun plaintextSendIsImportedAndWritesTwoRows() {
        val listener = startListener()
        try {
            val pending = listener.receiveAsync()
            val send = runSend(sendRoot(), listener.port)
            val outcome = pending.await()

            assertEquals(send.toString(), 0, send.exitCode)
            val json = send.json()
            assertEquals(2, jsonInt(json, "sourceCount") ?: -1)
            assertEquals(1, jsonInt(json, "frames") ?: -1)
            assertEquals(false, jsonBool(json, "tls") ?: true)
            assertEquals(false, jsonBool(json, "authenticate") ?: true)
            assertNull("没要 --confirm-delivery 时不该有投递证据", jsonBool(json, "deliveryConfirmed"))

            val imported = outcome as SourceSyncTransferImportOutcome.Imported
            assertEquals(2, imported.result.appliedCount)
            assertEquals(WINDOWS_DEVICE, imported.result.deviceId)
            val envelopeBytes = (imported.envelopeJson ?: "").toByteArray(Charsets.UTF_8).size
            assertEquals(envelopeBytes, jsonInt(json, "bytes") ?: -1)
            assertEquals(SourceSyncTransfer.chunkCount(envelopeBytes), jsonInt(json, "frames") ?: -1)

            val rows = runBlocking { db.serverDao().getAll() }
            assertEquals(2, rows.size)
            val emby = rows.single { row -> row.syncSourceId == RECORD_EMBY_ID }
            assertEquals("ke ting Emby", emby.name)
            assertEquals("EMBY", emby.type)
            assertEquals("emby", emby.syncSourceTypeRaw)
            assertEquals(3L, emby.syncRecordRevision)
            assertEquals("alice", emby.username)
            val endpoints = runBlocking { db.serverEndpointDao().getByServer(emby.id) }
            assertEquals(1, endpoints.size)
            assertEquals("http://127.0.0.1:8097", endpoints.single().url)
            assertTrue(endpoints.single().isPrimary)

            val jellyfin = rows.single { row -> row.syncSourceId == RECORD_JELLYFIN_ID }
            assertEquals("JELLYFIN", jellyfin.type)
            assertEquals(2L, jellyfin.syncRecordRevision)
        } finally {
            listener.close()
        }
    }

    @Test
    fun tlsPinnedSendIsAcceptedByTheListener() {
        val identity = identityOf(MAIN_FIXTURE)
        val pin = identity.certificateFingerprint
        assertNotNull("测试证书没有算出指纹", pin)
        val listener = startListener(tls = true, identity = identity)
        try {
            val pending = listener.receiveAsync()
            val send = runSend(sendRoot(), listener.port, listOf("--tls", "--expect-fingerprint", pin!!))
            val outcome = pending.await()

            assertEquals(send.toString(), 0, send.exitCode)
            val json = send.json()
            assertEquals(true, jsonBool(json, "tls") ?: false)
            assertEquals(pin, jsonText(json, "certificateFingerprint"))
            assertEquals(pin, jsonText(json, "expectFingerprint"))
            assertTrue(listener.service.tlsEnabled)
            assertEquals(pin, listener.service.certificateFingerprint)

            val imported = outcome as SourceSyncTransferImportOutcome.Imported
            assertEquals(2, imported.result.appliedCount)
            assertEquals(2, runBlocking { db.serverDao().count() })
        } finally {
            listener.close()
        }
    }

    @Test
    fun wrongPinIsRefusedBeforeAnyEnvelopeByteIsWritten() {
        val identity = identityOf(MAIN_FIXTURE)
        val pin = identity.certificateFingerprint!!
        val wrongPin = flipFirstHexDigit(pin)
        val listener = startListener(tls = true, identity = identity)
        try {
            val pending = listener.receiveAsync()
            val send = runSend(sendRoot(), listener.port, listOf("--tls", "--expect-fingerprint", wrongPin))
            val outcome = pending.await()

            assertNotEquals(send.toString(), 0, send.exitCode)
            assertTrue(send.stderr, send.stderr.contains("tls-fingerprint-mismatch"))
            assertEquals(0, runBlocking { db.serverDao().count() })
            val failed = outcome as SourceSyncTransferImportOutcome.TransferFailed
            // 指纹钉扎在客户端：Windows 的 SslStream 握手成功后才比对证书，随后立刻关流。
            // 所以服务端握手本身成功，接着读帧读到 EOF，给的是帧层码而不是 tls-fingerprint-mismatch；
            // 失配证据只在 Windows 侧（上面两条断言：非零退出码 + stderr 里的码）。
            assertTrue(
                "对端在 TLS 之上直接关流时本端给帧层码，实测：" + failed.reason,
                failed.reason == SourceSyncTransferReasons.FRAME_TRUNCATED ||
                    failed.reason == SourceSyncTransferReasons.IO_FAILED,
            )
        } finally {
            listener.close()
        }
    }

    @Test
    fun authenticatedSendIsImportedOnlyAfterTheTagFrameIsVerified() {
        val listener = startListener(authSecret = AUTH_SECRET)
        try {
            val pending = listener.receiveAsync()
            val send = runSend(
                root = sendRoot(),
                port = listener.port,
                extra = listOf("--authenticate", "--secret-env", SECRET_ENV),
                secret = AUTH_SECRET,
            )
            val outcome = pending.await()

            assertEquals(send.toString(), 0, send.exitCode)
            val json = send.json()
            assertEquals(true, jsonBool(json, "authenticate") ?: false)
            assertEquals(SECRET_ENV, jsonText(json, "secretEnv"))
            val imported = outcome as SourceSyncTransferImportOutcome.Imported
            val envelopeBytes = (imported.envelopeJson ?: "").toByteArray(Charsets.UTF_8).size
            assertEquals(
                "认证时 Windows 的 frames = 数据块数 + 挑战帧 + tag 帧",
                SourceSyncTransfer.chunkCount(envelopeBytes) + 2,
                jsonInt(json, "frames") ?: -1,
            )
            assertEquals(2, imported.result.appliedCount)
            assertEquals(2, runBlocking { db.serverDao().count() })
        } finally {
            listener.close()
        }
    }

    @Test
    fun wrongSecretIsRejectedByTheListenerAndNothingIsWritten() {
        val listener = startListener(authSecret = AUTH_SECRET)
        try {
            val pending = listener.receiveAsync()
            val send = runSend(
                root = sendRoot(),
                port = listener.port,
                extra = listOf("--authenticate", "--secret-env", SECRET_ENV),
                secret = WRONG_SECRET,
            )
            val outcome = pending.await()

            val failed = outcome as SourceSyncTransferImportOutcome.TransferFailed
            assertEquals(SourceSyncTransferAuthReasons.AUTH_MISMATCH, failed.reason)
            assertEquals(0, runBlocking { db.serverDao().count() })
            val json = send.jsonOrNull()
            if (json != null) {
                assertNull("没要 --confirm-delivery 时没有投递证据", jsonBool(json, "deliveryConfirmed"))
            }
            assertFalse(
                "Windows 发送端没有回执通道，看不到本端的 auth-mismatch；实测 exit=" + send.exitCode + " stderr=" + send.stderr,
                send.stderr.contains("auth-mismatch"),
            )
        } finally {
            listener.close()
        }
    }

    @Test
    fun replayedFramesAreRefusedAsAuthReplayed() {
        val listener = startListener(authSecret = AUTH_SECRET)
        val relay = RecordingRelay(listener.port)
        try {
            val pending = listener.receiveAsync()
            val send = runSend(
                root = sendRoot(),
                port = relay.port,
                extra = listOf("--authenticate", "--secret-env", SECRET_ENV),
                secret = AUTH_SECRET,
            )
            val first = pending.await()

            assertEquals(send.toString(), 0, send.exitCode)
            val imported = first as SourceSyncTransferImportOutcome.Imported
            assertEquals(2, imported.result.appliedCount)
            assertEquals(2, runBlocking { db.serverDao().count() })
            val before = baselineOf()

            relay.finish()
            val captured = relay.captured()
            assertTrue("中继没有截到 Windows 的字节", captured.isNotEmpty())
            assertEquals(
                "截下来的应当就是线上帧：认证时 = 4 + 9 + bytes + 27 + 37",
                (imported.envelopeJson ?: "").toByteArray(Charsets.UTF_8).size + 4 + 9 + 27 + 37,
                captured.size,
            )

            val replayPending = listener.receiveAsync()
            rawExchange(listener.port, captured)
            val replay = replayPending.await()

            val failed = replay as SourceSyncTransferImportOutcome.TransferFailed
            assertEquals(SourceSyncTransferAuthReasons.AUTH_REPLAYED, failed.reason)
            assertEquals(before, baselineOf())
            assertEquals("重放被拒在帧层，一条新行都不许写", 2, runBlocking { db.serverDao().count() })
        } finally {
            relay.close()
            listener.close()
        }
    }

    @Test
    fun plaintextSendAgainstATlsListenerIsRejectedOnTheAndroidSide() {
        val identity = identityOf(MAIN_FIXTURE)
        val listener = startListener(tls = true, identity = identity)
        try {
            val pending = listener.receiveAsync()
            val send = runSend(sendRoot(), listener.port)
            val outcome = pending.await()

            val failed = outcome as SourceSyncTransferImportOutcome.TransferFailed
            assertTrue("明文客户端打 TLS 监听端，实测码：" + failed.reason, failed.reason.startsWith("tls-"))
            assertEquals(0, runBlocking { db.serverDao().count() })
            val json = send.jsonOrNull()
            if (json != null) {
                assertNull("对端只是把字节写出去，没有任何投递/回执证据", jsonBool(json, "deliveryConfirmed"))
            }
        } finally {
            listener.close()
        }
    }

    @Test
    fun tlsAndAuthenticationComposeInOneConnection() {
        val identity = identityOf(MAIN_FIXTURE)
        val pin = identity.certificateFingerprint!!
        val listener = startListener(tls = true, identity = identity, authSecret = AUTH_SECRET)
        try {
            val pending = listener.receiveAsync()
            val send = runSend(
                root = sendRoot(),
                port = listener.port,
                extra = listOf("--tls", "--expect-fingerprint", pin, "--authenticate", "--secret-env", SECRET_ENV),
                secret = AUTH_SECRET,
            )
            val outcome = pending.await()

            assertEquals(send.toString(), 0, send.exitCode)
            val json = send.json()
            assertEquals(true, jsonBool(json, "tls") ?: false)
            assertEquals(true, jsonBool(json, "authenticate") ?: false)
            assertEquals(pin, jsonText(json, "certificateFingerprint"))
            val imported = outcome as SourceSyncTransferImportOutcome.Imported
            assertEquals(2, imported.result.appliedCount)
            assertEquals(2, runBlocking { db.serverDao().count() })
        } finally {
            listener.close()
        }
    }

    @Test
    fun theSecondSendOfTheSameSourcesWritesNothingNew() {
        val listener = startListener()
        try {
            val firstPending = listener.receiveAsync()
            val firstSend = runSend(sendRoot(), listener.port)
            val first = firstPending.await()
            assertEquals(firstSend.toString(), 0, firstSend.exitCode)
            assertEquals(2, (first as SourceSyncTransferImportOutcome.Imported).result.appliedCount)
            val before = baselineOf()

            val secondPending = listener.receiveAsync()
            val secondSend = runSend(sendRoot(), listener.port)
            val second = secondPending.await()
            assertEquals(secondSend.toString(), 0, secondSend.exitCode)

            val imported = second as SourceSyncTransferImportOutcome.Imported
            assertEquals(0, imported.result.appliedCount)
            assertEquals(2, imported.result.ignoredCount)
            assertEquals(before, baselineOf())
            assertEquals(2, runBlocking { db.serverDao().count() })
        } finally {
            listener.close()
        }
    }

    // ---------------------------------------------------------------- W6-40 回执（0x04）

    @Test
    fun windowsAwaitingAReceiptGetsConfirmedByTheKotlinListener() {
        val listener = startListener(receipt = true)
        try {
            val pending = listener.receiveAsync()
            val send = runSend(sendRoot(), listener.port, listOf("--await-receipt"))
            val outcome = pending.await()

            assertEquals(send.toString(), 0, send.exitCode)
            val json = send.json()
            assertEquals(true, jsonBool(json, "awaitReceipt") ?: false)
            assertEquals(
                "Windows 侧必须只凭线上一字节回执得出结论",
                true,
                jsonBool(json, "receiptConfirmed") ?: false,
            )
            val imported = outcome as SourceSyncTransferImportOutcome.Imported
            assertTrue("本端应记录已写回执", imported.receiptWritten)
            assertEquals(2, imported.result.appliedCount)
            assertEquals(2, runBlocking { db.serverDao().count() })
        } finally {
            listener.close()
        }
    }

    @Test
    fun kotlinAwaitingAReceiptConfirmsAgainstWindowsServe() {
        val root = newWorkspace("serve-root")
        val serve = startServe(root, sendReceipt = true)
        try {
            val client = SourceSyncTransferClient(connectTimeoutMs = 10_000, readTimeoutMs = 30_000)
            val sent = client.sendTo(
                "127.0.0.1",
                serve.port,
                androidEnvelope(),
                receipt = SourceSyncReceiptOptions.awaiting(20_000),
            )
            assertTrue("期待回执确认，实际 " + describeSent(sent), sent is SourceSyncTransferResult.Ok)

            val exit = serve.awaitExit()
            assertEquals(serve.diagnostics(), 0, exit)
            val json = serve.json()
            assertEquals(true, jsonBool(json, "sendReceipt") ?: false)
            assertEquals(
                "Windows 侧必须记录它写过回执，实测 JSON：" + json,
                true,
                jsonBool(json, "receiptWritten") ?: false,
            )
            assertEquals("对端 apply 之后应有两条来源落盘", 2, writtenSourceCount(root))
        } finally {
            serve.close()
        }
    }

    @Test
    fun kotlinAwaitingAReceiptWithoutAWindowsReceiptIsMissing() {
        val root = newWorkspace("serve-root")
        val serve = startServe(root, sendReceipt = false)
        try {
            val client = SourceSyncTransferClient(connectTimeoutMs = 10_000, readTimeoutMs = 30_000)
            val sent = client.sendTo(
                "127.0.0.1",
                serve.port,
                androidEnvelope(),
                receipt = SourceSyncReceiptOptions.awaiting(2_000),
            )
            assertTrue("期待失败裁决，实际 " + describeSent(sent), sent is SourceSyncTransferResult.Failed)
            assertEquals(
                SourceSyncReceiptReasons.RECEIPT_MISSING,
                (sent as SourceSyncTransferResult.Failed).reason,
            )

            val exit = serve.awaitExit()
            assertEquals(serve.diagnostics(), 0, exit)
            val json = serve.json()
            assertTrue(
                "没开 --send-receipt 时 Windows 不得写回执，实测 " + jsonBool(json, "receiptWritten"),
                jsonBool(json, "receiptWritten") != true,
            )
            assertEquals("对端仍然会把信封收下并落盘", 2, writtenSourceCount(root))
        } finally {
            serve.close()
        }
    }

    // ---------------------------------------------------------------- Windows serve 夹具

    private fun startServe(root: File, sendReceipt: Boolean): ServeCli {
        val cli = windowsCli() ?: throw AssertionError(SKIP_MESSAGE)
        val command = ArrayList<String>()
        command.add(dotnet())
        command.add(cli.absolutePath)
        command.add("transfer")
        command.add("serve")
        command.add("--root")
        command.add(root.absolutePath)
        command.add("--device")
        command.add(SERVE_DEVICE)
        command.add("--json")
        command.add("--apply")
        if (sendReceipt) command.add("--send-receipt")
        val builder = ProcessBuilder(command)
        builder.redirectErrorStream(false)
        builder.directory(root)
        val process = builder.start()
        val stdout = StreamDrain(process.inputStream)
        val stderr = StreamDrain(process.errorStream)
        val listening = stderr.awaitLine(LISTENING_PREFIX, 30_000)
            ?: throw AssertionError("Windows serve 没有报 listening：" + stderr.text())
        val port = listening.substringAfterLast(':').trim().toIntOrNull()
            ?: throw AssertionError("listening 行里没有端口：" + listening)
        return ServeCli(process, root, port, stdout, stderr)
    }

    private class ServeCli(
        private val process: Process,
        val root: File,
        val port: Int,
        private val stdout: StreamDrain,
        private val stderr: StreamDrain,
    ) : Closeable {

        fun awaitExit(timeoutMs: Long = 60_000): Int {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw AssertionError("Windows serve 没有在超时内退出：" + diagnostics())
            }
            stdout.await()
            stderr.await()
            return process.exitValue()
        }

        fun json(): String {
            val text = stdout.text()
            val start = text.indexOf('{')
            if (start < 0) throw AssertionError("serve 没有输出 JSON：" + diagnostics())
            return text.substring(start).trim()
        }

        fun diagnostics(): String =
            "exit=" + (if (process.isAlive) "alive" else process.exitValue().toString()) +
                " stdout=" + stdout.text() + " stderr=" + stderr.text()

        override fun close() {
            if (process.isAlive) {
                process.destroy()
                try {
                    process.waitFor(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                }
            }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** 本端当发送方时用的信封：两条已知来源，Windows apply 后应落两行。 */
    private fun androidEnvelope(): ByteArray = SourceSyncCodec.encode(
        SourceSyncEnvelope(
            deviceId = ANDROID_DEVICE,
            snapshotId = "snap-w640",
            createdAtEpochMs = 1_792_800_000_000L,
            capabilities = SyncCapabilities(canSendSecrets = false, sourceTypes = listOf("emby", "jellyfin")),
            sources = listOf(
                SourceRecord(
                    syncSourceId = SEND_RECORD_EMBY_ID,
                    sourceType = SyncSourceTypeValue.parse("emby"),
                    displayName = "w640 emby",
                    accountScope = "alice",
                    endpoints = listOf(SourceEndpoint(url = "http://127.0.0.1:8097", isPrimary = true, enabled = true)),
                    recordRevision = 4L,
                    fieldVersion = 1,
                ),
                SourceRecord(
                    syncSourceId = SEND_RECORD_JELLYFIN_ID,
                    sourceType = SyncSourceTypeValue.parse("jellyfin"),
                    displayName = "w640 jellyfin",
                    endpoints = listOf(SourceEndpoint(url = "http://127.0.0.1:8096", isPrimary = true, enabled = true)),
                    recordRevision = 2L,
                    fieldVersion = 1,
                ),
            ),
        ),
    ).toByteArray(Charsets.UTF_8)

    /** Windows FileLocalSourceStore 落盘条数：按 serverId 出现次数数。 */
    private fun writtenSourceCount(root: File): Int {
        val file = File(root, "sync-sources.json")
        if (!file.isFile) return 0
        val text = file.readText(Charsets.UTF_8)
        var count = 0
        var index = text.indexOf("\"serverId\"")
        while (index >= 0) {
            count++
            index = text.indexOf("\"serverId\"", index + 1)
        }
        return count
    }

    private fun describeSent(result: SourceSyncTransferResult): String = when (result) {
        is SourceSyncTransferResult.Ok -> "ok frames=" + result.frameCount
        is SourceSyncTransferResult.Failed -> result.reason + " / " + (result.message ?: "")
    }
    // ---------------------------------------------------------------- 监听端夹具

    private fun startListener(
        tls: Boolean = false,
        identity: SourceSyncTlsIdentity = SourceSyncTlsIdentity.NONE,
        authSecret: String? = null,
        receipt: Boolean = false,
    ): ListenerFixture {
        val pin = identity.certificateFingerprint
        assertTrue("TLS 监听必须有可用身份证书", !tls || pin != null)
        val started = SourceSyncListenerImportService.start(
            gateway = SourceSyncImportGateway { envelopeJson -> service.importEnvelope(envelopeJson) },
            tls = if (tls) SourceSyncTlsOptions.pinned(pin) else SourceSyncTlsOptions.Off,
            identity = identity,
            acceptTimeoutMs = 30_000,
            readTimeoutMs = 30_000,
            handshakeTimeoutMs = 20_000,
        )
        val startedService = when (started) {
            is SourceSyncListenerImportStart.Started -> started.service
            is SourceSyncListenerImportStart.Failed ->
                throw AssertionError("监听没有起来：" + started.code + " / " + started.detail)
        }
        val auth = authSecret?.let { secret ->
            SourceSyncTransferOptions.withSecret(
                secret.toByteArray(Charsets.UTF_8),
                SourceSyncNonceCache(16),
                deviceId = null,
            )
        }
        val receiptOptions = SourceSyncReceiptOptions.writingOrOff(receipt)
        return ListenerFixture(startedService, auth, receiptOptions)
    }

    private class ListenerFixture(
        val service: SourceSyncListenerImportService,
        private val auth: SourceSyncTransferOptions?,
        private val receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
    ) : Closeable {

        val port: Int get() = service.port

        fun receiveAsync(): PendingReceive = PendingReceive(service, auth, receipt)

        override fun close() {
            service.close()
        }
    }

    private class PendingReceive(
        private val service: SourceSyncListenerImportService,
        private val auth: SourceSyncTransferOptions?,
        private val receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
    ) {

        private val result = AtomicReference<SourceSyncTransferImportOutcome?>(null)
        private val failure = AtomicReference<Throwable?>(null)
        private val worker: Thread = Thread({
            try {
                result.set(runBlocking { service.receiveAndImport(null, auth, receipt) })
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "w638-receive")

        init {
            worker.isDaemon = true
            worker.start()
        }

        fun await(): SourceSyncTransferImportOutcome {
            worker.join(60_000)
            assertFalse("接收线程 60 秒没有返回", worker.isAlive)
            failure.get()?.let { error -> throw AssertionError("接收线程抛异常：" + error, error) }
            return result.get() ?: throw AssertionError("接收线程没有给出结果")
        }
    }

    // ---------------------------------------------------------------- Windows send 夹具

    private fun runSend(
        root: File,
        port: Int,
        extra: List<String> = emptyList(),
        secret: String? = null,
    ): SendResult {
        val cli = windowsCli() ?: throw AssertionError(SKIP_MESSAGE)
        val command = ArrayList<String>()
        command.add(dotnet())
        command.add(cli.absolutePath)
        command.add("transfer")
        command.add("send")
        command.add("--to")
        command.add("127.0.0.1:" + port)
        command.add("--root")
        command.add(root.absolutePath)
        command.add("--device")
        command.add(WINDOWS_DEVICE)
        command.add("--json")
        command.addAll(extra)
        val builder = ProcessBuilder(command)
        builder.redirectErrorStream(false)
        builder.directory(root)
        if (secret != null) {
            builder.environment()[SECRET_ENV] = secret
        }
        val process = builder.start()
        val stdout = StreamDrain(process.inputStream)
        val stderr = StreamDrain(process.errorStream)
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("Windows send 60 秒没有退出")
        }
        stdout.await()
        stderr.await()
        return SendResult(process.exitValue(), stdout.text(), stderr.text())
    }

    private fun windowsCli(): File? {
        val configured = System.getenv(ENV_SYNC_DLL)?.trim().orEmpty()
        if (configured.isEmpty()) return null
        val file = File(configured)
        return if (file.isFile) file else null
    }

    private fun dotnet(): String {
        val configured = System.getenv(ENV_DOTNET)?.trim().orEmpty()
        return if (configured.isEmpty()) "dotnet" else configured
    }

    private class SendResult(val exitCode: Int, private val stdout: String, val stderr: String) {

        fun jsonOrNull(): String? {
            val start = stdout.indexOf('{')
            return if (start < 0) null else stdout.substring(start).trim()
        }

        fun json(): String = jsonOrNull() ?: throw AssertionError("send 没有输出 JSON：" + stdout + " / " + stderr)

        override fun toString(): String = "exit=" + exitCode + " stdout=" + stdout + " stderr=" + stderr
    }

    private class StreamDrain(stream: InputStream) {

        private val buffer = ByteArrayOutputStream()
        private val worker: Thread = Thread({
            try {
                stream.use { input ->
                    val chunk = ByteArray(8_192)
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        buffer.write(chunk, 0, read)
                    }
                }
            } catch (_: Exception) {
            }
        }, "w638-drain")

        init {
            worker.isDaemon = true
            worker.start()
        }

        fun await() {
            worker.join(15_000)
        }

        /** 轮询等到某一行出现（CLI 的 listening 行是子进程启动后才写的，不能一次读完就判断）。 */
        fun awaitLine(prefix: String, timeoutMs: Long): String? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val found = lineWithPrefix(prefix)
                if (found != null) return found
                Thread.sleep(50)
            }
            return lineWithPrefix(prefix)
        }

        private fun lineWithPrefix(prefix: String): String? = text().lines()
            .map { line -> line.trim() }
            .firstOrNull { line -> line.startsWith(prefix) }

        fun text(): String = buffer.toString("UTF-8")
    }

    private fun sendRoot(): File {
        val root = newWorkspace("send-root")
        File(root, "sync-sources.json").writeText(sendRootJson(), Charsets.UTF_8)
        return root
    }

    /**
     * Windows 侧 FileLocalSourceStore 的落盘格式：一个 JSON 数组，字段按 camelCase。
     * 两条记录的 revise 分别是 3 与 2，落库后可直接当基线断言。
     */
    private fun sendRootJson(): String = """[
  {"serverId":"srv-w638-emby","syncSourceId":"local-w638-emby","type":"Emby","url":"http://127.0.0.1:8097","displayName":"ke ting Emby","recordRevision":3,"accountScope":"alice"},
  {"serverId":"srv-w638-jellyfin","syncSourceId":"local-w638-jellyfin","type":"Jellyfin","url":"http://127.0.0.1:8096","displayName":"shu fang Jellyfin","recordRevision":2}
]"""

    // ---------------------------------------------------------------- 中继与裸 socket

    /**
     * 把 Windows 发来的字节原样转发给监听端，同时录一份。
     *
     * 存在的唯一理由：CLI 的 nonce 每次随机（没有 --nonce 开关），要构造 auth-replayed
     * 只能把真实帧录下来再原样重放。
     */
    private class RecordingRelay(private val targetPort: Int) : Closeable {

        private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        private val capturedBytes = ByteArrayOutputStream()
        private val failure = AtomicReference<Throwable?>(null)
        private val worker: Thread

        val port: Int get() = server.localPort

        init {
            worker = Thread({
                try {
                    server.accept().use { client ->
                        Socket(InetAddress.getLoopbackAddress(), targetPort).use { target ->
                            val upstream = pumpAsync(client.getInputStream(), target, true)
                            val downstream = pumpAsync(target.getInputStream(), client, false)
                            upstream.join(60_000)
                            downstream.join(60_000)
                        }
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }, "w638-relay")
            worker.isDaemon = true
            worker.start()
        }

        private fun pumpAsync(input: InputStream, sink: Socket, capture: Boolean): Thread {
            val thread = Thread({
                val chunk = ByteArray(8_192)
                try {
                    val output = sink.getOutputStream()
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        if (capture) {
                            capturedBytes.write(chunk, 0, read)
                        }
                        output.write(chunk, 0, read)
                        output.flush()
                    }
                    try {
                        sink.shutdownOutput()
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                }
            }, "w638-relay-pump")
            thread.isDaemon = true
            thread.start()
            return thread
        }

        fun finish() {
            try {
                server.close()
            } catch (_: Exception) {
            }
            worker.join(60_000)
            failure.get()?.let { error -> throw AssertionError("中继线程失败：" + error, error) }
        }

        fun captured(): ByteArray = capturedBytes.toByteArray()

        override fun close() {
            finish()
        }
    }

    private fun rawExchange(port: Int, payload: ByteArray): ByteArray {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 30_000
            val output = socket.getOutputStream()
            output.write(payload)
            output.flush()
            socket.shutdownOutput()
            return socket.getInputStream().readBytes()
        }
    }

    // ---------------------------------------------------------------- 小工具

    private fun identityOf(fixture: String): SourceSyncTlsIdentity {
        val stream = javaClass.getResourceAsStream("/tls/" + fixture + ".p12")
        assertNotNull("test resources 里没有 tls/" + fixture + ".p12", stream)
        val keyStore = KeyStore.getInstance("PKCS12")
        stream!!.use { loaded -> keyStore.load(loaded, P12_PASSWORD.toCharArray()) }
        val identity = SourceSyncTlsIdentity.fromKeyStore(keyStore, P12_PASSWORD.toCharArray())
        assertTrue("测试证书加载失败（identity 不可用）", identity.isUsable)
        return identity
    }

    private fun flipFirstHexDigit(hex: String): String {
        val replacement = if (hex[0] == '2') '3' else '2'
        return replacement + hex.substring(1)
    }

    private fun baselineOf(): List<Pair<String?, Long>> = runBlocking { db.serverDao().getAll() }
        .map { row -> row.syncSourceId to row.syncRecordRevision }
        .sortedBy { entry -> entry.first ?: "" }

    private fun newWorkspace(name: String): File {
        val parent = File(System.getProperty("java.io.tmpdir") ?: ".", "mh-w638")
        parent.mkdirs()
        val directory = File(parent, name + "-" + System.nanoTime())
        directory.mkdirs()
        workspaces.add(directory)
        return directory
    }

    private fun quote(): String = 34.toChar().toString()

    private fun fieldOf(json: String, key: String): String? {
        val marker = quote() + key + quote() + ":"
        val start = json.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        if (valueStart >= json.length) return null
        if (json[valueStart] == 34.toChar()) {
            val end = json.indexOf(34.toChar(), valueStart + 1)
            return if (end < 0) null else json.substring(valueStart + 1, end)
        }
        val comma = json.indexOf(',', valueStart)
        val stop = if (comma < 0) json.length else comma
        return json.substring(valueStart, stop).trim()
    }

    private fun jsonText(json: String, key: String): String? = fieldOf(json, key)

    private fun jsonInt(json: String, key: String): Int? = fieldOf(json, key)?.toIntOrNull()

    private fun jsonBool(json: String, key: String): Boolean? {
        val raw = fieldOf(json, key) ?: return null
        return when (raw) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    companion object {
        private const val ENV_SYNC_DLL = "MH_W638_SYNC_DLL"
        private const val ENV_DOTNET = "MH_W638_DOTNET"
        private const val SECRET_ENV = "MH_W638_SECRET"
        private const val AUTH_SECRET = "interop-secret-alpha"
        private const val WRONG_SECRET = "interop-secret-bravo"
        private const val ANDROID_DEVICE = "device-android"
        private const val WINDOWS_DEVICE = "win-c"
        private const val RECORD_EMBY_ID = "local-w638-emby"
        private const val RECORD_JELLYFIN_ID = "local-w638-jellyfin"
        private const val SERVE_DEVICE = "win-a"
        private const val LISTENING_PREFIX = "listening "
        private const val SEND_RECORD_EMBY_ID = "local-w640-emby"
        private const val SEND_RECORD_JELLYFIN_ID = "local-w640-jellyfin"
        private const val MAIN_FIXTURE = "mediahub-sync-test"
        private const val P12_PASSWORD = "mediahub-tls-test"
        private const val SKIP_MESSAGE =
            "MH_W638_SYNC_DLL 没有指向 MediaHub.SyncCli.dll，跳过反向跨端联调（CI 上必须跳过）"
    }
}
