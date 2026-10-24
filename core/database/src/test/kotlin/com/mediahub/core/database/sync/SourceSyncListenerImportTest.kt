package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.AndroidSourceTypeMap
import com.mediahub.core.common.sync.SourceSyncListenerStart
import com.mediahub.core.common.sync.SourceSyncNonceCache
import com.mediahub.core.common.sync.SourceSyncTransferAuthReasons
import com.mediahub.core.common.sync.SourceSyncTransferClient
import com.mediahub.core.common.sync.SourceSyncTransferListener
import com.mediahub.core.common.sync.SourceSyncTransferOptions
import com.mediahub.core.common.sync.SourceSyncTransferReasons
import com.mediahub.model.MediaServer
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-34：监听端（core:common）与导入端（core:database）接起来的闭环测试。
 *
 * 这里跑的是全链路：真回环 socket → SourceSyncTransferListener 解帧 → importEnvelopeBytes 解码 →
 * AndroidSourceSyncService 规划并写库。所以断言既有传输层的稳定码，也有库里的实体数与调用次数。
 */
class SourceSyncListenerImportTest {

    @Test
    fun plaintextEnvelopeIsImportedAndAppliesTwoSources() = runBlocking {
        val store = ImportFakeStore()
        val service = serviceOver(store)
        try {
            val sent = SendCall(service.port, goldenEnvelope()).start()
            val imported = assertImported(service.receiveAndImport())
            sent.await()

            assertEquals("应写入两条来源", 2, imported.appliedCount)
            assertEquals("quantum-nas 不在映射表里，必须走审批", 1, imported.requiresApprovalCount)
            assertEquals(1, imported.ignoredCount)
            assertEquals(0, imported.failedCount)
            assertEquals(2, store.createCalls)
            assertEquals(0, store.updateCalls)
            assertEquals(2, store.snapshots.size)
            assertEquals("device-a", imported.deviceId)
        } finally {
            service.close()
        }
    }

    @Test
    fun secondRunOfTheSameEnvelopeWritesNothingNew() = runBlocking {
        val store = ImportFakeStore()
        val service = serviceOver(store)
        try {
            val firstSend = SendCall(service.port, goldenEnvelope()).start()
            val first = assertImported(service.receiveAndImport())
            firstSend.await()
            assertEquals(2, first.appliedCount)
            val createAfterFirst = store.createCalls

            val secondSend = SendCall(service.port, goldenEnvelope()).start()
            val second = assertImported(service.receiveAndImport())
            secondSend.await()

            assertEquals("第二轮不应再写", 0, second.appliedCount)
            assertEquals("同一个待审来源仍在桶里", 1, second.requiresApprovalCount)
            assertEquals("两条已存在 + 墓碑未知 = 三条被忽略", 3, second.ignoredCount)
            assertEquals(createAfterFirst, store.createCalls)
            assertEquals(0, store.updateCalls)
        } finally {
            service.close()
        }
    }

    @Test
    fun rejectedEnvelopeIsNotHandedToTheGateway() = runBlocking {
        val store = ImportFakeStore()
        val gateway = RecordingGateway()
        val service = gatewayService(gateway)
        try {
            val sent = SendCall(service.port, "null".toByteArray(Charsets.UTF_8)).start()
            val outcome = service.receiveAndImport()
            sent.await()

            assertTrue(
                "合法 JSON 但不是信封，必须在解码层被拒，实际 " + describe(outcome),
                outcome is SourceSyncTransferImportOutcome.EnvelopeRejected,
            )
            assertEquals("malformed-json", (outcome as SourceSyncTransferImportOutcome.EnvelopeRejected).reason)
            assertEquals("解码失败就不能碰网关", 0, gateway.calls)
            assertEquals(0, store.createCalls)
            assertEquals(0, store.snapshots.size)
        } finally {
            service.close()
        }
    }

    @Test
    fun truncatedStreamIsReportedAsTransferFailureWithoutWrites() = runBlocking {
        val store = ImportFakeStore()
        val gateway = RecordingGateway()
        val service = gatewayService(gateway)
        try {
            val sent = RawCall(service.port, byteArrayOf(0, 0, 0, 0)).start()
            val outcome = service.receiveAndImport()
            sent.await()

            assertTrue(
                "零长度帧必须落成传输层失败，实际 " + describe(outcome),
                outcome is SourceSyncTransferImportOutcome.TransferFailed,
            )
            assertEquals(
                SourceSyncTransferReasons.FRAME_TRUNCATED,
                (outcome as SourceSyncTransferImportOutcome.TransferFailed).reason,
            )
            assertEquals(0, gateway.calls)
            assertEquals(0, store.createCalls)
        } finally {
            service.close()
        }
    }

    @Test
    fun nonLoopbackHostIsRefusedBeforeAnySocketExists() {
        val gateway = RecordingGateway()
        val started = SourceSyncListenerImportService.start(gateway, host = "10.1.2.3")
        assertTrue("非回环地址必须被拒", started is SourceSyncListenerImportStart.Failed)
        assertEquals(
            SourceSyncTransferReasons.NOT_LOOPBACK,
            (started as SourceSyncListenerImportStart.Failed).code,
        )
        assertEquals(0, gateway.calls)
    }

    @Test
    fun busyPortIsReportedAsListenerFailed() {
        val first = SourceSyncListenerImportService.start(RecordingGateway())
        val service = (first as SourceSyncListenerImportStart.Started).service
        try {
            val second = SourceSyncListenerImportService.start(RecordingGateway(), port = service.port)
            assertTrue(second is SourceSyncListenerImportStart.Failed)
            assertEquals(
                SourceSyncTransferReasons.LISTENER_FAILED,
                (second as SourceSyncListenerImportStart.Failed).code,
            )
        } finally {
            service.close()
        }
    }

    @Test
    fun wrongSecretIsRefusedAsAuthMismatchWithoutWrites() = runBlocking {
        val store = ImportFakeStore()
        val gateway = RecordingGateway()
        val service = gatewayService(gateway)
        try {
            val sent = SendCall(
                service.port,
                goldenEnvelope(),
                challenge = NONCE,
                auth = SourceSyncTransferOptions.withSecret(
                    "interop-secret-bravo".toByteArray(Charsets.UTF_8),
                    SourceSyncNonceCache(),
                    "android-a",
                ),
            ).start()
            val outcome = service.receiveAndImport(
                auth = SourceSyncTransferOptions.withSecret(secretOf(), SourceSyncNonceCache(), "win-a"),
            )
            sent.awaitQuietly()

            assertTrue(
                "口令不一致必须是传输层失败，实际 " + describe(outcome),
                outcome is SourceSyncTransferImportOutcome.TransferFailed,
            )
            assertEquals(
                SourceSyncTransferAuthReasons.AUTH_MISMATCH,
                (outcome as SourceSyncTransferImportOutcome.TransferFailed).reason,
            )
            assertEquals(0, gateway.calls)
            assertEquals(0, store.createCalls)
        } finally {
            service.close()
        }
    }

    @Test
    fun startExposesTheBoundPortAndCloseReleasesIt() {
        val started = SourceSyncListenerImportService.start(RecordingGateway())
        val service = (started as SourceSyncListenerImportStart.Started).service
        val port = service.port
        try {
            assertTrue("端口应由系统分配", port in 1..65535)
            assertEquals("127.0.0.1", service.address)
            assertFalse(service.isClosed)
        } finally {
            service.close()
        }
        assertTrue(service.isClosed)

        // close 之后同端口必须能立刻重新绑定：没有残留的 accept 线程占着它。
        val again = SourceSyncListenerImportService.start(RecordingGateway(), port = port)
        try {
            assertTrue("close 之后端口应可复用，实际 " + describeStart(again), again is SourceSyncListenerImportStart.Started)
            assertEquals(port, (again as SourceSyncListenerImportStart.Started).service.port)
        } finally {
            (again as SourceSyncListenerImportStart.Started).service.close()
        }
    }

    // ---------------------------------------------------------------- 夹具

    private fun serviceOver(store: ImportFakeStore): SourceSyncListenerImportService {
        val imports = SourceSyncTransferImportService(AndroidSourceSyncService(store, ANDROID_DEVICE))
        return serviceOverImports(imports)
    }

    private fun gatewayService(gateway: SourceSyncImportGateway): SourceSyncListenerImportService =
        serviceOverImports(SourceSyncTransferImportService(gateway))

    private fun serviceOverImports(imports: SourceSyncTransferImportService): SourceSyncListenerImportService {
        val started = SourceSyncTransferListener.open(acceptTimeoutMs = 10_000, readTimeoutMs = 10_000)
        assertTrue("监听没能起来：" + describeStart(started), started is SourceSyncListenerStart.Started)
        return SourceSyncListenerImportService(imports, (started as SourceSyncListenerStart.Started).listener)
    }

    private fun assertImported(outcome: SourceSyncTransferImportOutcome): SourceSyncImportResult {
        assertTrue("期待导入成功，实际 " + describe(outcome), outcome is SourceSyncTransferImportOutcome.Imported)
        return (outcome as SourceSyncTransferImportOutcome.Imported).result
    }

    private fun describe(outcome: SourceSyncTransferImportOutcome): String = when (outcome) {
        is SourceSyncTransferImportOutcome.TransferFailed ->
            "transfer-failed " + outcome.reason + " / " + (outcome.detail ?: "")
        is SourceSyncTransferImportOutcome.EnvelopeRejected ->
            "envelope-rejected " + outcome.reason + " / " + (outcome.message ?: "")
        is SourceSyncTransferImportOutcome.Imported ->
            "imported applied=" + outcome.result.appliedCount
    }

    private fun describeStart(started: com.mediahub.core.common.sync.SourceSyncListenerStart): String = when (started) {
        is SourceSyncListenerStart.Started -> "ok port=" + started.listener.port
        is SourceSyncListenerStart.Failed -> started.code + " / " + (started.detail ?: "")
    }

    private fun describeStart(started: SourceSyncListenerImportStart): String = when (started) {
        is SourceSyncListenerImportStart.Started -> "ok port=" + started.service.port
        is SourceSyncListenerImportStart.Failed -> started.code + " / " + (started.detail ?: "")
    }

    private fun goldenEnvelope(): ByteArray {
        val stream = javaClass.getResourceAsStream("/" + GOLDEN_RESOURCE)
        assertTrue("夹具不存在：" + GOLDEN_RESOURCE, stream != null)
        return stream!!.use { it.readBytes() }
    }

    private fun secretOf(): ByteArray = "interop-secret-alpha".toByteArray(Charsets.UTF_8)

    /** 在后台线程里用真客户端拨号发送；主线程跑 receiveAndImport。 */
    private class SendCall(
        private val port: Int,
        private val payload: ByteArray,
        private val challenge: String? = null,
        private val auth: SourceSyncTransferOptions? = null,
    ) {
        private val failure = AtomicReference<Throwable?>(null)
        private val client = SourceSyncTransferClient(connectTimeoutMs = 5_000, readTimeoutMs = 10_000)
        private val thread = Thread {
            try {
                client.sendTo(HOST, port, payload, challenge, auth = auth)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }.apply {
            isDaemon = true
            name = "w6-34-db-send"
        }

        fun start(): SendCall {
            thread.start()
            return this
        }

        fun await() {
            thread.join(30_000)
            assertFalse("发送线程没有在超时内结束", thread.isAlive)
            failure.get()?.let { throw AssertionError("发送线程抛了异常", it) }
        }

        fun awaitQuietly() {
            thread.join(30_000)
        }
    }

    /** 裸 socket 送几个字节再半关写端，用于构造坏帧。 */
    private class RawCall(private val port: Int, private val bytes: ByteArray) {
        private val thread = Thread {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000)
                    socket.soTimeout = 10_000
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                    socket.shutdownOutput()
                    drain(socket.getInputStream())
                }
            } catch (error: java.io.IOException) {
                // 对端先断开也算正常收尾。
            }
        }.apply {
            isDaemon = true
            name = "w6-34-db-raw"
        }

        fun start(): RawCall {
            thread.start()
            return this
        }

        fun await() {
            thread.join(30_000)
            assertFalse("裸 socket 线程没有在超时内结束", thread.isAlive)
        }

        private fun drain(input: InputStream) {
            val buffer = ByteArray(256)
            try {
                while (input.read(buffer) >= 0) {
                    // 只关心监听端给出的码。
                }
            } catch (error: java.io.IOException) {
                // RST 也算读完。
            }
        }
    }

    /** 只记账的网关：断言"网关没被调"是失败路径零写入的判据。 */
    private class RecordingGateway(private val result: SourceSyncImportResult = SourceSyncImportResult()) :
        SourceSyncImportGateway {
        val envelopes = ArrayList<String>()
        val calls: Int get() = envelopes.size

        override suspend fun importEnvelope(envelopeJson: String): SourceSyncImportResult {
            envelopes.add(envelopeJson)
            return result
        }
    }

    /** 内存版 SourceSyncStore：只够导入服务用，记 create/update 调用数。 */
    private class ImportFakeStore : SourceSyncStore {
        val snapshots = LinkedHashMap<String, SourceSyncSourceSnapshot>()

        var createCalls = 0
            private set

        var updateCalls = 0
            private set

        private var sequence = 0

        override suspend fun listSourceSnapshots(): List<SourceSyncSourceSnapshot> = snapshots.values.toList()

        override suspend fun listLocalSources(): List<LocalSourceRecord> = snapshots.values.map { it.toLocalRecord() }

        override suspend fun findBySyncSourceId(syncSourceId: String): LocalSourceRecord? {
            val found = snapshots.values.firstOrNull { it.syncSourceId == syncSourceId } ?: return null
            return found.toLocalRecord()
        }

        override suspend fun createLocalSource(snapshot: SourceSyncSourceSnapshot): String {
            createCalls++
            sequence++
            val id = "srv-new-" + sequence
            snapshots[id] = snapshot.copy(serverId = id)
            return id
        }

        override suspend fun updateLocalSource(serverId: String, snapshot: SourceSyncSourceSnapshot): Boolean {
            updateCalls++
            if (!snapshots.containsKey(serverId)) return false
            snapshots[serverId] = snapshot.copy(serverId = serverId)
            return true
        }

        override suspend fun bindSyncIdentity(
            serverId: String,
            syncSourceId: String,
            recordRevision: Long,
            sourceTypeRaw: String?,
        ): Boolean = snapshots.containsKey(serverId)

        override fun observeServers(): Flow<List<MediaServer>> = flowOf(emptyList())

        override suspend fun getServer(id: String): MediaServer? = null

        private fun SourceSyncSourceSnapshot.toLocalRecord(): LocalSourceRecord {
            val resolvedType = sourceTypeRaw?.takeIf { it.isNotBlank() }
                ?.let { AndroidSourceTypeMap.toServerType(it) }
                ?: serverType
            val activeUrl = endpoints.firstOrNull { it.isPrimary && it.enabled }?.url
                ?: endpoints.firstOrNull { it.enabled }?.url
            return LocalSourceRecord(
                serverId = serverId,
                syncSourceId = syncSourceId,
                type = resolvedType,
                url = activeUrl,
                accountScope = accountScope,
                displayName = name,
                recordRevision = recordRevision,
                sourceTypeRaw = sourceTypeRaw,
            )
        }
    }

    private companion object {
        const val HOST: String = "127.0.0.1"
        const val ANDROID_DEVICE: String = "device-a"
        const val NONCE: String = "nonce-auth-0001"
        const val GOLDEN_RESOURCE: String = "sync/windows-envelope-device-a.json"
    }
}
