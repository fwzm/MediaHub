package com.mediahub.core.common.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Android（本仓 Kotlin，JSSE）与 Windows（D:/mh-w6 的 MediaHub.SyncCli，SslStream）之间的跨端联调。
 *
 * 这不是单元测试，是进程级联调：每条用例起一个真正的 dotnet MediaHub.SyncCli.dll transfer serve
 * 子进程，用本仓的 [SourceSyncTransferClient] 连它的回环端口，然后同时检查两端的事实：
 * 客户端收到的稳定码、serve 退出码、serve 的 JSON 结果，以及落盘的 sync-sources.json 里到底有几条来源。
 *
 * 纪律：
 * - 找不到 Windows CLI 时跳过（[assumeTrue]），所以 CI 上这个套件永远 skipped，不需要 Windows 产物；
 * - 不碰 Windows 仓库的任何文件，只起它已经编好的 dll；
 * - 每例都杀子进程 + 删临时目录，不留常驻进程；
 * - 只连回环，不碰真实网卡。
 */
class SourceSyncTlsInteropTest {

    private var cliDll: String = ""
    private var dotnetExe: String = "dotnet"

    @Before
    fun requireTheWindowsCli() {
        val dll = System.getenv(ENV_SYNC_DLL)
        assumeTrue(
            ENV_SYNC_DLL + " 没有指向 MediaHub.SyncCli.dll，跳过跨端联调（CI 上必须跳过）",
            dll != null && dll.isNotBlank(),
        )
        val path = java.nio.file.Paths.get(dll!!)
        assumeTrue(
            dll + " 不存在；先 dotnet build windows/src/MediaHub.SyncCli",
            Files.isRegularFile(path),
        )
        cliDll = path.toAbsolutePath().toString()
        dotnetExe = System.getenv(ENV_DOTNET)?.takeIf { it.isNotBlank() } ?: "dotnet"
    }

    // ---- 用例 1：明文 Kotlin -> Windows，Windows 真落库 ----

    @Test
    fun plaintextKotlinEnvelopeIsAppliedByWindows() {
        val root = newRoot("plain")
        val serve = startServe(root)
        try {
            val payload = envelopeOf(ANDROID_DEVICE, "snap-w632-plain")
            val result = SourceSyncTransferClient().sendTo("127.0.0.1", serve.port(), payload)
            val exit = serve.awaitExit()
            val json = serve.stdoutJson()
            assertTrue("客户端应当发送成功：" + describe(result), result is SourceSyncTransferResult.Ok)
            assertEquals("serve 应当成功退出；stderr=" + serve.stderrText(), 0, exit)
            assertTrue("JSON 应当说这次真的 apply 了", json.bool("applied"))
            assertEquals(2, json.obj("counts").int("apply"))
            assertEquals(2, json.arr("items").size)
            // 明文没有挑战帧：实测 Windows 的 frames 只数它读到的帧，因此这里就是数据块数。
            assertEquals(SourceSyncTransfer.chunkCount(payload.size), json.int("frames"))
            assertEquals(listOf("local-w632-emby", "local-w632-jellyfin"), writtenSources(root))
        } finally {
            serve.close()
        }
    }

    // ---- 用例 2：TLS 抓 pin 握手成功送达 ----

    @Test
    fun tlsPinnedHandshakeDeliversTheEnvelope() {
        val root = newRoot("tls")
        val serve = startServe(root, tls = true)
        try {
            val port = serve.port()
            val pin = serve.fingerprint()
            assertEquals("指纹应当是 64 位小写 hex，实际 " + pin, 64, pin.length)
            assertEquals(pin.lowercase(), pin)
            val payload = envelopeOf(ANDROID_DEVICE, "snap-w632-tls")
            val result = SourceSyncTransferClient().sendTo(
                "127.0.0.1",
                port,
                payload,
                tls = SourceSyncTlsOptions.pinned(pin),
            )
            val exit = serve.awaitExit()
            val json = serve.stdoutJson()
            assertTrue("TLS 上应当发送成功：" + describe(result), result is SourceSyncTransferResult.Ok)
            assertEquals("serve 应当成功退出；stderr=" + serve.stderrText(), 0, exit)
            assertTrue(json.bool("tls"))
            assertEquals(pin, json.text("certificateFingerprint"))
            assertEquals(2, json.obj("counts").int("apply"))
            assertEquals(2, writtenSources(root).size)
        } finally {
            serve.close()
        }
    }

    // ---- 用例 3：错一位 pin 在握手期就被拒，接收端零写入 ----

    @Test
    fun wrongPinIsRefusedBeforeAnyEnvelopeByteIsSent() {
        val root = newRoot("bad-pin")
        val serve = startServe(root, tls = true)
        try {
            val pin = serve.fingerprint()
            val wrongPin = (if (pin[0] == 'a') 'b' else 'a') + pin.substring(1)
            assertFalse("改一位之后必须真的变了", wrongPin == pin)
            val payload = envelopeOf(ANDROID_DEVICE, "snap-w632-bad-pin")
            val result = SourceSyncTransferClient().sendTo(
                "127.0.0.1",
                serve.port(),
                payload,
                tls = SourceSyncTlsOptions.pinned(wrongPin),
            )
            val exit = serve.awaitExit()
            assertTrue("应当是 Failed，实际 " + describe(result), result is SourceSyncTransferResult.Failed)
            assertEquals(
                SourceSyncTlsReasons.FINGERPRINT_MISMATCH,
                (result as SourceSyncTransferResult.Failed).reason,
            )
            assertFalse("接收端不该成功退出，实际 exit=" + exit, exit == 0)
            assertTrue("接收端不该写出同步身份，实际 " + writtenSources(root), writtenSources(root).isEmpty())
            assertFalse("sync-sources.json 不该被建出来", Files.exists(root.resolve(SOURCES_FILE)))
        } finally {
            serve.close()
        }
    }

    // ---- 用例 4：TLS 客户端打明文端点 ----

    @Test
    fun tlsClientAgainstAPlaintextServeFailsTheHandshake() {
        val root = newRoot("tls-vs-plain")
        val serve = startServe(root, tls = false)
        try {
            val result = SourceSyncTransferClient().sendTo(
                "127.0.0.1",
                serve.port(),
                envelopeOf(ANDROID_DEVICE, "snap-w632-tls-vs-plain"),
                tls = SourceSyncTlsOptions.pinned(LOOPBACK_TEST_PIN),
            )
            val exit = serve.awaitExit()
            assertTrue("应当是 Failed，实际 " + describe(result), result is SourceSyncTransferResult.Failed)
            assertEquals(
                SourceSyncTlsReasons.HANDSHAKE_FAILED,
                (result as SourceSyncTransferResult.Failed).reason,
            )
            assertFalse("明文 serve 不该把这次连接当成功，实际 exit=" + exit, exit == 0)
            assertTrue("接收端不该写库，实际 " + writtenSources(root), writtenSources(root).isEmpty())
        } finally {
            serve.close()
        }
    }

    // ---- 用例 5：明文客户端打 TLS 端点（Windows 侧拒绝） ----

    @Test
    fun plaintextClientAgainstATlsServeIsRejectedOnTheWindowsSide() {
        val root = newRoot("plain-vs-tls")
        val serve = startServe(root, tls = true)
        try {
            val result = SourceSyncTransferClient().sendTo(
                "127.0.0.1",
                serve.port(),
                envelopeOf(ANDROID_DEVICE, "snap-w632-plain-vs-tls"),
            )
            val exit = serve.awaitExit()
            // 协议是单向的：sendTo 的 Ok 只表示「帧写进了 socket」，不代表对端接受（没有投递确认，
            // Windows CLI 的 --confirm-delivery 在 Android 侧尚无对应物）。所以客户端这里就是 Ok，
            // 真正的拒绝只能从 Windows 侧观察 —— 这个不对称记在 docs 第 18 节。
            assertTrue("发送端本地能写成功，实际 " + describe(result), result is SourceSyncTransferResult.Ok)
            assertEquals(1, (result as SourceSyncTransferResult.Ok).frameCount)
            assertFalse("TLS serve 不该把这次连接当成功，实际 exit=" + exit, exit == 0)
            assertTrue(
                "Windows 侧应当报 tls-handshake-failed；实际 stderr=" + serve.stderrText(),
                serve.stderrText().contains(SourceSyncTlsReasons.HANDSHAKE_FAILED),
            )
            assertTrue("接收端不该写库，实际 " + writtenSources(root), writtenSources(root).isEmpty())
        } finally {
            serve.close()
        }
    }

    // ---- 用例 6：认证开启（Windows 挑战文本当 nonce） ----

    @Test
    fun authenticatedKotlinTagIsVerifiedByWindows() {
        val root = newRoot("auth")
        val serve = startServe(root, secret = INTEROP_SECRET)
        try {
            val nonce = vectorNonce()
            val payload = envelopeOf(ANDROID_DEVICE, "snap-w632-auth")
            val result = SourceSyncTransferClient().sendTo(
                "127.0.0.1",
                serve.port(),
                payload,
                challenge = nonce,
                auth = SourceSyncTransferOptions.withSecret(secretBytes(), deviceId = ANDROID_DEVICE),
            )
            val exit = serve.awaitExit()
            val json = serve.stdoutJson()
            assertTrue("认证链路上应当发送成功：" + describe(result), result is SourceSyncTransferResult.Ok)
            assertEquals("serve 应当成功退出；stderr=" + serve.stderrText(), 0, exit)
            assertTrue(json.bool("authenticate"))
            assertEquals(SECRET_ENV, json.text("secretEnv"))
            assertEquals(2, json.obj("counts").int("apply"))
            // 挑战帧 + 数据块 + tag 帧：比明文的 挑战帧 + 数据块 多一帧，证明 tag 真的发出去了。
            assertEquals(SourceSyncTransfer.chunkCount(payload.size) + 2, json.int("frames"))
            assertEquals(2, writtenSources(root).size)
        } finally {
            serve.close()
        }
    }

    // ---- 用例 7：口令不一致 -> Windows 报 auth-mismatch ----

    @Test
    fun wrongSecretIsRefusedAsAuthMismatch() {
        val root = newRoot("bad-secret")
        val serve = startServe(root, secret = "interop-secret-bravo")
        try {
            val result = SourceSyncTransferClient().sendTo(
                "127.0.0.1",
                serve.port(),
                envelopeOf(ANDROID_DEVICE, "snap-w632-bad-secret"),
                challenge = vectorNonce(),
                auth = SourceSyncTransferOptions.withSecret(secretBytes(), deviceId = ANDROID_DEVICE),
            )
            val exit = serve.awaitExit()
            assertFalse("接收端不该成功退出，实际 exit=" + exit, exit == 0)
            assertTrue(
                "Windows 应当报 auth-mismatch；实际 stderr=" + serve.stderrText(),
                serve.stderrText().contains(SourceSyncTransferAuthReasons.AUTH_MISMATCH),
            )
            assertTrue("验签失败时不该写库，实际 " + writtenSources(root), writtenSources(root).isEmpty())
            // 同上：发送端没有投递确认，口令错了它也会报 Ok(3 帧)。拒绝只发生在 Windows 一侧。
            assertTrue("发送端本地能写成功，实际 " + describe(result), result is SourceSyncTransferResult.Ok)
            assertEquals(3, (result as SourceSyncTransferResult.Ok).frameCount)
        } finally {
            serve.close()
        }
    }

    // ---- 用例 8：TLS 与认证同开 ----

    @Test
    fun tlsAndAuthenticationCompose() {
        val root = newRoot("tls-auth")
        val serve = startServe(root, tls = true, secret = INTEROP_SECRET)
        try {
            val pin = serve.fingerprint()
            val result = SourceSyncTransferClient().sendTo(
                "127.0.0.1",
                serve.port(),
                envelopeOf(ANDROID_DEVICE, "snap-w632-tls-auth"),
                challenge = vectorNonce(),
                tls = SourceSyncTlsOptions.pinned(pin),
                auth = SourceSyncTransferOptions.withSecret(secretBytes(), deviceId = ANDROID_DEVICE),
            )
            val exit = serve.awaitExit()
            val json = serve.stdoutJson()
            assertTrue("TLS + 认证应当成功：" + describe(result), result is SourceSyncTransferResult.Ok)
            assertEquals("serve 应当成功退出；stderr=" + serve.stderrText(), 0, exit)
            assertTrue(json.bool("tls"))
            assertTrue(json.bool("authenticate"))
            assertEquals(2, json.obj("counts").int("apply"))
            assertEquals(2, writtenSources(root).size)
        } finally {
            serve.close()
        }
    }

    // ---- 用例 9：复放窗口是进程级的（盲区如实记录） ----

    @Test
    fun theSameNonceIsAcceptedByTwoSeparateServeProcesses() {
        val nonce = vectorNonce()
        for (round in 1..2) {
            val root = newRoot("replay-" + round)
            val serve = startServe(root, secret = INTEROP_SECRET)
            try {
                val result = SourceSyncTransferClient().sendTo(
                    "127.0.0.1",
                    serve.port(),
                    envelopeOf(ANDROID_DEVICE, "snap-w632-replay-" + round),
                    challenge = nonce,
                    auth = SourceSyncTransferOptions.withSecret(secretBytes(), deviceId = ANDROID_DEVICE),
                )
                assertEquals("第 " + round + " 轮应当成功；stderr=" + serve.stderrText(), 0, serve.awaitExit())
                assertTrue("第 " + round + " 轮客户端应当成功：" + describe(result), result is SourceSyncTransferResult.Ok)
                assertEquals(2, writtenSources(root).size)
            } finally {
                serve.close()
            }
        }
        // 注意：CLI 的 serve 每条进程只服务一条连接就退出，nonce 缓存是进程级的，所以这里证明不了
        // 「同一进程内第二次同 nonce 被拒」。那个语义由 SourceSyncTransferAuthTest 的 auth-replayed 用例
        // 与 Windows 自己的测试覆盖；本用例只如实钉住「进程级窗口」这个事实。
    }

    // ---- 用例 10：反向——Windows send 钉扎 Kotlin 的 TLS 监听端 ----

    @Test
    fun windowsSendPinsTheKotlinTlsListener() {
        val identity = identityOf(MAIN_FIXTURE)
        val pin = identity.certificateFingerprint ?: throw AssertionError("夹具应当能给出指纹")
        val root = newRoot("inbound")
        val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        listener.soTimeout = 30_000
        val received = AtomicReference<SourceSyncTransferResult?>(null)
        val failure = AtomicReference<Throwable?>(null)
        val worker = thread(isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    socket.soTimeout = 30_000
                    socket.tcpNoDelay = true
                    when (val negotiated = SourceSyncTls.handshakeServer(socket, identity, pin, 20_000)) {
                        is SourceSyncTlsResult.Failed ->
                            received.set(SourceSyncTransferResult.Failed(negotiated.reason, negotiated.detail))
                        is SourceSyncTlsResult.Ok -> {
                            received.set(SourceSyncTransfer.receive(negotiated.session.input))
                            // TLS 1.3 服务端必须先半关并读到 EOF 再关，否则对端看到 RST（W6-26 第 8 节）。
                            SourceSyncTls.gracefulClose(negotiated.session.socket)
                        }
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                runCatching { listener.close() }
            }
        }
        var send: WindowsCli? = null
        try {
            send = startSend(root, listener.localPort, WINDOWS_SENDER_DEVICE, pin)
            val exit = send.awaitExit()
            worker.join(30_000)
            failure.get()?.let { throw AssertionError("Kotlin 监听端失败：" + it, it) }
            assertEquals("Windows send 应当成功；stderr=" + send.stderrText(), 0, exit)
            val result = received.get()
            assertTrue("Kotlin 应当收下信封，实际 " + describeNullable(result), result is SourceSyncTransferResult.Ok)
            val bytes = (result as SourceSyncTransferResult.Ok).envelope
            when (val decoded = SourceSyncCodec.tryDecode(String(bytes, StandardCharsets.UTF_8))) {
                is SourceSyncCodec.DecodeResult.Failed ->
                    throw AssertionError("Windows 送来的信封解不开：" + decoded.reason)
                is SourceSyncCodec.DecodeResult.Ok ->
                    assertEquals(WINDOWS_SENDER_DEVICE, decoded.envelope.deviceId)
            }
            assertTrue("发送方不该往共享目录写来源", writtenSources(root).isEmpty())
        } finally {
            send?.close()
            runCatching { listener.close() }
        }
    }

    // ---- 夹具与工具 ----

    private fun startServe(
        root: Path,
        device: String = WINDOWS_DEVICE,
        tls: Boolean = false,
        secret: String? = null,
    ): WindowsCli {
        val command = mutableListOf(
            dotnetExe,
            cliDll,
            "transfer",
            "serve",
            "--root",
            root.toString(),
            "--device",
            device,
            "--json",
            "--apply",
        )
        if (tls) {
            command.add("--tls")
        }
        if (secret != null) {
            command.add("--authenticate")
            command.add("--secret-env")
            command.add(SECRET_ENV)
        }
        val builder = ProcessBuilder(command).directory(root.toFile())
        builder.redirectErrorStream(false)
        if (secret != null) {
            builder.environment()[SECRET_ENV] = secret
        }
        return WindowsCli(builder.start(), root)
    }

    private fun startSend(root: Path, port: Int, device: String, pin: String?): WindowsCli {
        val command = mutableListOf(
            dotnetExe,
            cliDll,
            "transfer",
            "send",
            "--to",
            "127.0.0.1:" + port,
            "--root",
            root.toString(),
            "--device",
            device,
            "--json",
        )
        if (pin != null) {
            command.add("--tls")
            command.add("--expect-fingerprint")
            command.add(pin)
        }
        val builder = ProcessBuilder(command).directory(root.toFile())
        builder.redirectErrorStream(false)
        return WindowsCli(builder.start(), root)
    }

    private fun newRoot(name: String): Path = Files.createTempDirectory("mh-w632-" + name + "-")

    private fun envelopeOf(deviceId: String, snapshotId: String): ByteArray {
        val envelope = SourceSyncEnvelope(
            schemaVersion = SourceSyncEnvelope.CURRENT_SCHEMA_VERSION,
            minimumReaderVersion = 1,
            deviceId = deviceId,
            snapshotId = snapshotId,
            createdAtEpochMs = 1_792_800_000_000L,
            capabilities = SyncCapabilities(
                canSendSecrets = false,
                sourceTypes = listOf("emby", "jellyfin"),
            ),
            sources = listOf(
                recordOf("local-w632-emby", "emby", "客厅 Emby", "http://127.0.0.1:8097", 3L),
                recordOf("local-w632-jellyfin", "jellyfin", "书房 Jellyfin", "http://127.0.0.1:8096", 2L),
            ),
        )
        return SourceSyncCodec.encode(envelope).toByteArray(StandardCharsets.UTF_8)
    }

    private fun recordOf(
        syncSourceId: String,
        sourceType: String,
        displayName: String,
        url: String,
        revision: Long,
    ): SourceRecord = SourceRecord(
        syncSourceId = syncSourceId,
        sourceType = SyncSourceTypeValue.parse(sourceType),
        displayName = displayName,
        accountScope = "default",
        endpoints = listOf(SourceEndpoint(url = url, isPrimary = true, enabled = true, sortOrder = 0)),
        recordRevision = revision,
        fieldVersion = 1,
    )

    private fun writtenSources(root: Path): List<String> {
        val file = root.resolve(SOURCES_FILE)
        if (!Files.exists(file)) {
            return emptyList()
        }
        // Files.readString 是 Java 11 API，Android 的 android.jar 里没有，这里用字节数组构造。
        val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim()
        if (text.isEmpty()) {
            return emptyList()
        }
        val array = Json.parseToJsonElement(text).jsonArray
        return array.map { it.jsonObject.required("syncSourceId").jsonPrimitive.content }
    }

    private fun secretBytes(): ByteArray = INTEROP_SECRET.toByteArray(StandardCharsets.UTF_8)

    private fun vectorNonce(): String {
        val vectors = Json.parseToJsonElement(resourceText(AUTH_VECTORS)).jsonObject
        return vectors.obj("nonces").text("first")
    }

    private fun identityOf(name: String): SourceSyncTlsIdentity {
        val vectors = Json.parseToJsonElement(resourceText(TLS_VECTORS)).jsonObject
        val fixture = vectors.arr("fixtures")
            .map { it.jsonObject }
            .firstOrNull { it.text("name") == name }
            ?: throw AssertionError("tls-vectors.json 里没有夹具 " + name)
        val password = fixture["pkcs12Password"]?.jsonPrimitive?.content ?: vectors.text("pkcs12Password")
        val keyStore = KeyStore.getInstance("PKCS12")
        resourceStream(FIXTURE_DIR + fixture.text("file")).use { stream ->
            keyStore.load(stream, password.toCharArray())
        }
        val identity = SourceSyncTlsIdentity.fromKeyStore(keyStore, password.toCharArray())
        assertTrue("夹具 " + name + " 应当能装出可用的 TLS 身份", identity.isUsable)
        assertEquals(fixture.text("spkiSha256Hex"), identity.certificateFingerprint)
        return identity
    }

    private fun resourceStream(path: String): InputStream =
        javaClass.getResourceAsStream(path) ?: throw AssertionError("测试资源不存在：" + path)

    private fun resourceText(path: String): String =
        resourceStream(path).use { it.readBytes().toString(StandardCharsets.UTF_8) }

    private fun describe(result: SourceSyncTransferResult): String = when (result) {
        is SourceSyncTransferResult.Ok -> "Ok(frameCount=" + result.frameCount + ")"
        is SourceSyncTransferResult.Failed -> "Failed(" + result.reason + ", " + result.message + ")"
    }

    private fun describeNullable(result: SourceSyncTransferResult?): String =
        if (result == null) "null（没有结果）" else describe(result)

    private fun JsonObject.required(key: String): JsonElement =
        this[key] ?: throw AssertionError("JSON 里没有字段 " + key + "；实际=" + this)

    private fun JsonObject.obj(key: String): JsonObject = required(key).jsonObject

    private fun JsonObject.int(key: String): Int = required(key).jsonPrimitive.int

    private fun JsonObject.bool(key: String): Boolean = required(key).jsonPrimitive.boolean

    private fun JsonObject.text(key: String): String = required(key).jsonPrimitive.content

    private fun JsonObject.arr(key: String): JsonArray = required(key).jsonArray

    /**
     * 一个已经起好的 Windows CLI 子进程。
     *
     * stderr 是进度与 listening / certificate fingerprint 的通道，stdout 是唯一的 JSON 文档；
     * 两个流都由 daemon 线程持续读，避免管道写满把子进程卡死。
     */
    private class WindowsCli(
        private val process: Process,
        private val workspace: Path,
    ) {

        private val stderrLines: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        private val stdoutLines: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        init {
            thread(isDaemon = true) { readInto(process.errorStream, stderrLines) }
            thread(isDaemon = true) { readInto(process.inputStream, stdoutLines) }
        }

        fun awaitLine(prefix: String, timeoutMs: Long = 30_000L): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val hit = snapshot(stderrLines).firstOrNull { it.startsWith(prefix) }
                if (hit != null) {
                    return hit
                }
                if (!process.isAlive) {
                    throw AssertionError(
                        "进程已退出（exit=" + process.exitValue() + "），stderr 里没有以 " + prefix +
                            " 开头的行；stderr=" + stderrText(),
                    )
                }
                Thread.sleep(20L)
            }
            throw AssertionError(
                "等了 " + timeoutMs + " ms 也没等到 stderr 行 " + prefix + "；stderr=" + stderrText(),
            )
        }

        fun port(): Int = awaitLine(LISTENING_PREFIX).substringAfterLast(':').trim().toInt()

        fun fingerprint(): String = awaitLine(FINGERPRINT_PREFIX).substringAfter(':').trim()

        fun awaitExit(timeoutMs: Long = 30_000L): Int {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                throw AssertionError("进程在 " + timeoutMs + " ms 内没有退出；stderr=" + stderrText())
            }
            Thread.sleep(50L)
            return process.exitValue()
        }

        fun stdoutText(): String = snapshot(stdoutLines).joinToString("\n")

        fun stderrText(): String = snapshot(stderrLines).joinToString(" | ")

        fun stdoutJson(): JsonObject {
            val text = stdoutText().trim()
            val start = text.indexOf('{')
            if (start < 0) {
                throw AssertionError("stdout 里没有 JSON 文档；stdout=" + text + "；stderr=" + stderrText())
            }
            return Json.parseToJsonElement(text.substring(start).trim()).jsonObject
        }

        fun close() {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
            val directory = workspace.toFile()
            if (directory.exists()) {
                directory.walkBottomUp().forEach { child -> runCatching { child.delete() } }
            }
        }

        private fun readInto(stream: InputStream, sink: MutableList<String>) {
            try {
                stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        sink.add(line)
                    }
                }
            } catch (ignored: java.io.IOException) {
                // 进程被杀时管道会断，这是预期内的收尾噪声。
            }
        }

        private fun snapshot(lines: MutableList<String>): List<String> = synchronized(lines) { lines.toList() }
    }

    private companion object {
        /** 指向 MediaHub.SyncCli.dll 的绝对路径；没有就跳过整个套件（CI 上永远跳过）。 */
        const val ENV_SYNC_DLL: String = "MH_W632_SYNC_DLL"

        /** 可选：dotnet 可执行文件的路径或名字，默认 dotnet。 */
        const val ENV_DOTNET: String = "MH_W632_DOTNET"

        /** 子进程读口令用的环境变量名（口令本身永远不出现在命令行里）。 */
        const val SECRET_ENV: String = "MH_W632_SECRET"

        const val INTEROP_SECRET: String = "interop-secret-alpha"

        const val SOURCES_FILE: String = "sync-sources.json"

        /** serve 一启动就在 stderr 写的两行，端口与指纹都从这里抓。 */
        const val LISTENING_PREFIX: String = "listening "
        const val FINGERPRINT_PREFIX: String = "certificate fingerprint: "

        const val WINDOWS_DEVICE: String = "win-a"
        const val WINDOWS_SENDER_DEVICE: String = "win-b"
        const val ANDROID_DEVICE: String = "android-a"

        /** 用例 4 需要一枚格式合法的 pin（对端根本没有 TLS，指纹值本身无所谓）。 */
        const val LOOPBACK_TEST_PIN: String =
            "2bb12d139178ee3b0f219e409a89c99b51be165914e21189eeb039b2e4dd37ce"

        const val MAIN_FIXTURE: String = "mediahub-sync-test"
        const val FIXTURE_DIR: String = "/tls/"
        const val TLS_VECTORS: String = "/tls/tls-vectors.json"
        const val AUTH_VECTORS: String = "/sync/windows-auth-vectors.json"
    }
}
