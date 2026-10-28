package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.AndroidSourceTypeMap
import com.mediahub.core.common.sync.LocalSourceRecord
import com.mediahub.core.common.sync.SourceSyncTransfer
import com.mediahub.core.common.sync.SourceSyncTransferReasons
import com.mediahub.model.MediaServer
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-23：传输 → 解码 → 导入三段闭环的回归。
 *
 * 覆盖三类证据：
 * 1) 真实回环：Windows 侧冻结的黄金信封（sha256 见 GOLDEN_SHA256）经真实帧流进真实导入服务，
 *    第一轮 applied=2 / requiresApproval=1 / ignored=1，第二轮 applied=0 / requiresApproval=1 / ignored=3；
 * 2) 传输失败（拨号前 / 帧层）零写入：任何一条都在网关调用数上断言 0，不看文案；
 * 3) 载荷不是信封（非 JSON、缺字段、未来版本、非法 UTF-8、空载荷）零导入：网关调用数 0。
 *
 * 回环夹具是本文件私有的 LoopbackPeer（只绑 127.0.0.1:0，跑完必关），不复用其它模块的测试类：
 * core:common 的测试源码不在 core:database 的可见范围内。
 *
 * W6-96：本文件里真正写库的用例都经手写的 [writeThrough] 网关 —— 那个能直接吃 AndroidSourceSyncService
 * 的次构造器已从生产 API 删除。保留对照是为了让「零写入」断言有牙：同一封信封经写库网关会落两条。
 */
class SourceSyncTransferImportTest {

    @Test
    fun golden_envelope_over_a_loopback_socket_imports_two_sources_and_holds_one_for_approval() {
        val store = ImportFakeStore()
        val service = AndroidSourceSyncService(store, DEVICE_ID)
        val peer = LoopbackPeer(serving(framesOf(goldenEnvelopeBytes(), CHALLENGE)))
        try {
            val outcome = runBlocking {
                SourceSyncTransferImportService(writeThrough(service)).receiveAndImport(HOST, peer.port, CHALLENGE)
            }
            peer.await()

            assertTrue(outcome.isImported)
            val result = (outcome as SourceSyncTransferImportOutcome.Imported).result
            assertFalse(result.isRejected)
            assertNull(result.rejectedReason)
            assertEquals(2, result.appliedCount)
            assertEquals(1, result.requiresApprovalCount)
            assertEquals(1, result.ignoredCount)
            assertEquals(0, result.failedCount)
            assertEquals(4, result.totalCount)
            assertTrue(result.hasWrites)
            assertEquals(2, store.createCalls)
            assertEquals(0, store.updateCalls)
        } finally {
            peer.closeQuietly()
        }
    }

    @Test
    fun the_same_envelope_a_second_time_writes_nothing() {
        val store = ImportFakeStore()
        val importer = SourceSyncTransferImportService(writeThrough(AndroidSourceSyncService(store, DEVICE_ID)))

        val first = roundTrip(importer)
        val second = roundTrip(importer)

        assertEquals(2, (first as SourceSyncTransferImportOutcome.Imported).result.appliedCount)
        val secondResult = (second as SourceSyncTransferImportOutcome.Imported).result
        assertEquals(0, secondResult.appliedCount)
        assertEquals(1, secondResult.requiresApprovalCount)
        assertEquals(3, secondResult.ignoredCount)
        assertEquals(0, secondResult.failedCount)
        assertFalse(secondResult.hasWrites)
        assertNull(secondResult.rejectedReason)
        // 幂等性判据是内容摘要而不是字节：同一份信封两次得到同一个 hash。
        assertEquals(first.result.envelopeContentHash, secondResult.envelopeContentHash)
        // 第二轮一条都没写：CREATE 仍只有第一轮那两条，UPDATE 从头到尾为 0。
        assertEquals(2, store.createCalls)
        assertEquals(0, store.updateCalls)
    }

    @Test
    fun a_closed_port_is_a_transfer_failure_and_the_importer_is_never_called() {
        val gateway = RecordingGateway()
        val probe = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = probe.localPort
        probe.close()

        val outcome = runBlocking {
            SourceSyncTransferImportService(gateway).receiveAndImport(HOST, port, CHALLENGE)
        }

        assertTrue(outcome.isTransferFailure)
        assertEquals(SourceSyncTransferReasons.CONNECT_FAILED, (outcome as SourceSyncTransferImportOutcome.TransferFailed).reason)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun a_non_loopback_host_is_refused_before_the_importer_is_called() {
        val gateway = RecordingGateway()

        val outcome = runBlocking {
            SourceSyncTransferImportService(gateway).receiveAndImport("10.1.2.3", 8942, CHALLENGE)
        }

        assertTrue(outcome.isTransferFailure)
        assertEquals(SourceSyncTransferReasons.NOT_LOOPBACK, outcome.reasonOrNull)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun a_challenge_mismatch_is_a_transfer_failure_and_nothing_is_imported() {
        val gateway = RecordingGateway()

        val outcome = importOverLoopback(gateway, framesOf(goldenEnvelopeBytes(), CHALLENGE), "some-other-challenge")

        assertEquals(SourceSyncTransferReasons.CHALLENGE_MISMATCH, outcome.reasonOrNull)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun a_frame_header_of_zero_length_is_a_transfer_failure() {
        val gateway = RecordingGateway()

        val outcome = importOverLoopback(gateway, byteArrayOf(0x00, 0x00, 0x00, 0x00))

        assertEquals(SourceSyncTransferReasons.FRAME_TRUNCATED, outcome.reasonOrNull)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun a_frame_declaring_more_than_the_maximum_is_a_transfer_failure() {
        val gateway = RecordingGateway()

        // 8193 = PairingFrame.MAXIMUM_PAYLOAD_BYTES + 1，超过上限必须当场拒绝，不能先分配再读。
        val outcome = importOverLoopback(gateway, byteArrayOf(0x00, 0x00, 0x20, 0x01))

        assertEquals(SourceSyncTransferReasons.FRAME_TRUNCATED, outcome.reasonOrNull)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun a_peer_that_says_nothing_is_a_transfer_failure() {
        val gateway = RecordingGateway()

        val outcome = importOverLoopback(gateway, ByteArray(0))

        assertEquals(SourceSyncTransferReasons.FRAME_TRUNCATED, outcome.reasonOrNull)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun bytes_after_a_complete_envelope_do_not_change_the_result() {
        val store = ImportFakeStore()
        val frames = framesOf(goldenEnvelopeBytes(), CHALLENGE)
        val withTrailingGarbage = frames + byteArrayOf(0x00, 0x00, 0x00, 0x05)
        val peer = LoopbackPeer(serving(withTrailingGarbage))
        try {
            val outcome = runBlocking {
                SourceSyncTransferImportService(writeThrough(AndroidSourceSyncService(store, DEVICE_ID)))
                    .receiveAndImport(HOST, peer.port, CHALLENGE)
            }
            peer.await()
            assertEquals(2, (outcome as SourceSyncTransferImportOutcome.Imported).result.appliedCount)
            assertEquals(2, store.createCalls)
        } finally {
            peer.closeQuietly()
        }
    }

    @Test
    fun malformed_json_is_rejected_before_the_importer_is_called() {
        val gateway = RecordingGateway()

        val outcome = importBytes(gateway, "{not-json".toByteArray(Charsets.UTF_8))

        assertTrue(outcome.isEnvelopeRejection)
        assertEquals("malformed-json", outcome.reasonOrNull)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun a_json_document_that_is_not_an_envelope_reports_the_missing_field() {
        val gateway = RecordingGateway()

        val outcome = importBytes(gateway, schemaVersionMissingEnvelope().toByteArray(Charsets.UTF_8))

        assertTrue(outcome.isEnvelopeRejection)
        assertEquals("missing-field:schemaVersion", outcome.reasonOrNull)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun a_future_schema_version_is_rejected_before_the_importer_is_called() {
        val gateway = RecordingGateway()

        val outcome = importBytes(gateway, schemaVersionNineEnvelope().toByteArray(Charsets.UTF_8))

        assertTrue(outcome.isEnvelopeRejection)
        assertEquals("unsupported-schema-version:9", outcome.reasonOrNull)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun an_empty_payload_is_rejected_before_the_importer_is_called() {
        val gateway = RecordingGateway()

        val outcome = importBytes(gateway, ByteArray(0))

        assertEquals("malformed-json", outcome.reasonOrNull)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun invalid_utf8_bytes_are_rejected_before_the_importer_is_called() {
        val gateway = RecordingGateway()

        // 0xB1 不能出现在 UTF-8 序列里：解码后不是 JSON，绝不能被当成 U+FFFD 混过去。
        val outcome = importBytes(gateway, byteArrayOf(0x7B, 0xB1.toByte(), 0x7D))

        assertEquals("malformed-json", outcome.reasonOrNull)
        assertEquals(0, gateway.calls)
    }

    @Test
    fun a_legal_envelope_reaches_the_importer_with_the_exact_text() {
        val gateway = RecordingGateway()
        val bytes = goldenEnvelopeBytes()

        val outcome = importBytes(gateway, bytes)

        assertTrue(outcome.isImported)
        assertNull(outcome.reasonOrNull)
        assertEquals(1, gateway.calls)
        assertEquals(bytes.toString(Charsets.UTF_8), gateway.envelopes[0])
    }

    @Test
    fun a_rejection_inside_the_import_service_is_still_an_imported_outcome() {
        val store = ImportFakeStore()
        val service = AndroidSourceSyncService(store, DEVICE_ID)

        val outcome = importEnvelopeBytesOf(service, duplicateSyncSourceIdEnvelope().toByteArray(Charsets.UTF_8))

        assertTrue(outcome.isImported)
        assertEquals("duplicate-sync-source-id", outcome.reasonOrNull)
        val result = (outcome as SourceSyncTransferImportOutcome.Imported).result
        assertTrue(result.isRejected)
        assertEquals(0, result.appliedCount)
        assertEquals(0, store.createCalls)
    }

    @Test
    fun an_exception_from_the_importer_is_not_swallowed() {
        val throwing = ThrowingGateway()

        val failure = expectThrows { importBytes(throwing, goldenEnvelopeBytes()) }

        assertTrue(failure is IllegalStateException)
        assertEquals("importer exploded", failure.message)
    }

    @Test
    fun outcome_kinds_carry_exactly_one_stable_reason() {
        val transfer = importOverLoopback(RecordingGateway(), byteArrayOf(0x00, 0x00, 0x00, 0x00))
        val rejection = importBytes(RecordingGateway(), "{".toByteArray(Charsets.UTF_8))
        val imported = importBytes(RecordingGateway(), goldenEnvelopeBytes())

        assertFalse(transfer.isImported)
        assertFalse(transfer.isEnvelopeRejection)
        assertEquals(SourceSyncTransferReasons.FRAME_TRUNCATED, transfer.reasonOrNull)

        assertFalse(rejection.isTransferFailure)
        assertFalse(rejection.isImported)
        assertEquals("malformed-json", rejection.reasonOrNull)

        assertTrue(imported.isImported)
        assertNull(imported.reasonOrNull)
    }

    @Test
    fun the_golden_envelope_test_copy_still_matches_its_recorded_digest() {
        val bytes = goldenEnvelopeBytes()

        assertEquals(1093, bytes.size)
        assertEquals(GOLDEN_SHA256, sha256Hex(bytes))
    }


    // ---------------------------------------------------------------- 夹具

    private fun roundTrip(importer: SourceSyncTransferImportService): SourceSyncTransferImportOutcome {
        val peer = LoopbackPeer(serving(framesOf(goldenEnvelopeBytes(), CHALLENGE)))
        try {
            val outcome = runBlocking { importer.receiveAndImport(HOST, peer.port, CHALLENGE) }
            peer.await()
            return outcome
        } finally {
            peer.closeQuietly()
        }
    }

    private fun importOverLoopback(
        gateway: SourceSyncImportGateway,
        payload: ByteArray,
        expectedChallenge: String? = CHALLENGE,
    ): SourceSyncTransferImportOutcome {
        val peer = LoopbackPeer(serving(payload))
        try {
            val outcome = runBlocking {
                SourceSyncTransferImportService(gateway).receiveAndImport(HOST, peer.port, expectedChallenge)
            }
            peer.await()
            return outcome
        } finally {
            peer.closeQuietly()
        }
    }

    private fun importBytes(gateway: SourceSyncImportGateway, bytes: ByteArray): SourceSyncTransferImportOutcome =
        runBlocking { SourceSyncTransferImportService(gateway).importEnvelopeBytes(bytes) }

    private fun importEnvelopeBytesOf(
        service: AndroidSourceSyncService,
        bytes: ByteArray,
    ): SourceSyncTransferImportOutcome =
        runBlocking { SourceSyncTransferImportService(writeThrough(service)).importEnvelopeBytes(bytes) }

    /**
     * W6-96：把写库服务接进接收口的**手写**网关（旧次构造器的对照）。
     *
     * W6-96 之前这层接线由 SourceSyncTransferImportService 的次构造器提供；生产从未用过它（界面一直走
     * SourceSyncImportGateways.acceptOnly() + 预览/逐条允许后的 applyRound），所以次构造器在 W6-96 被删除。
     * 这里显式写出来的唯一目的，是保住「这条老路确实会写库」的对照证据：它证明本文件的零写入断言不是空转。
     */
    private fun writeThrough(service: AndroidSourceSyncService): SourceSyncImportGateway =
        SourceSyncImportGateway { envelopeJson -> service.importEnvelope(envelopeJson) }

    private fun framesOf(envelope: ByteArray, challenge: String? = CHALLENGE): ByteArray =
        SourceSyncTransfer.encode(envelope, challenge)

    /**
     * 写出 payload 后半关闭写端，再读到对端关连接。
     * 直接 close 会让未读数据触发 RST，客户端可能看不到完整的最后一帧。
     */
    private fun serving(payload: ByteArray): (Socket) -> Unit = { socket ->
        try {
            socket.getOutputStream().write(payload)
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            val input = socket.getInputStream()
            val drain = ByteArray(256)
            while (input.read(drain) != -1) {
                // 读空对端，等它主动关连接。
            }
        } catch (ignored: java.io.IOException) {
            // 对端先关（非回环、超时用例）：属预期，不算夹具失败。
        }
    }

    private fun goldenEnvelopeBytes(): ByteArray {
        val stream = SourceSyncTransferImportTest::class.java.classLoader
            ?.getResourceAsStream(GOLDEN_RESOURCE)
            ?: throw IllegalStateException("test resource missing: " + GOLDEN_RESOURCE)
        return stream.use { it.readBytes() }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) builder.append(String.format("%02x", byte))
        return builder.toString()
    }

    private fun expectThrows(block: () -> Unit): Throwable {
        try {
            block()
        } catch (thrown: Throwable) {
            return thrown
        }
        throw AssertionError("expected the call to fail but it returned normally")
    }

    // ---------------------------------------------------------------- 坏载荷

    /** 只有 deviceId：第一个必需字段 schemaVersion 就缺了。 */
    private fun schemaVersionMissingEnvelope(): String = """{"deviceId":"device-a"}"""

    /** 合法 JSON、字段齐全，但 schemaVersion 是 9（未来版本）。 */
    private fun schemaVersionNineEnvelope(): String =
        """{"schemaVersion":9,"minimumReaderVersion":1,"deviceId":"device-a","snapshotId":"snap-9",""" +
            """"createdAtEpochMs":1792784739843,"capabilities":{"canSendSecrets":false,"sourceTypes":["emby"]},""" +
            """"sources":[],"deletions":[]}"""

    /** 两条记录共用同一个 syncSourceId：导入服务必须在写库前整封拒绝。 */
    private fun duplicateSyncSourceIdEnvelope(): String =
        """{"schemaVersion":1,"minimumReaderVersion":1,"deviceId":"device-a","snapshotId":"snap-duplicate",""" +
            """"createdAtEpochMs":1792784739843,"capabilities":{"canSendSecrets":false,"sourceTypes":["emby"]},""" +
            """"sources":[""" +
            """{"syncSourceId":"local-1111111111111111","sourceType":"emby","displayName":"A",""" +
            """"endpoints":[{"url":"http://nas-a:8096/emby","isPrimary":true,"enabled":true,"sortOrder":0}],""" +
            """"recordRevision":1,"fieldVersion":0},""" +
            """{"syncSourceId":"local-1111111111111111","sourceType":"emby","displayName":"B",""" +
            """"endpoints":[{"url":"http://nas-b:8096/emby","isPrimary":true,"enabled":true,"sortOrder":0}],""" +
            """"recordRevision":2,"fieldVersion":0}],""" +
            """"deletions":[]}"""

    // ---------------------------------------------------------------- 测试替身

    /** 只记账、不碰库的网关：断言"导入服务被调了几次"是失败路径零写入的判据。 */
    private class RecordingGateway(
        private val result: SourceSyncImportResult = SourceSyncImportResult(),
    ) : SourceSyncImportGateway {
        val envelopes = ArrayList<String>()
        val calls: Int get() = envelopes.size

        override suspend fun importEnvelope(envelopeJson: String): SourceSyncImportResult {
            envelopes.add(envelopeJson)
            return result
        }
    }

    /** 网关自己抛异常：传输层不得把它吞成某个稳定码。 */
    private class ThrowingGateway : SourceSyncImportGateway {
        override suspend fun importEnvelope(envelopeJson: String): SourceSyncImportResult =
            throw IllegalStateException("importer exploded")
    }

    /** 只绑 127.0.0.1:0 的回环对端；跑完必须 closeQuietly，否则端口和线程挂着。 */
    private class LoopbackPeer(private val handler: (Socket) -> Unit) {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val failure = AtomicReference<Throwable?>(null)
        private val thread = Thread {
            try {
                server.accept().use { socket -> handler(socket) }
            } catch (thrown: Throwable) {
                failure.set(thrown)
            } finally {
                runCatching { server.close() }
            }
        }

        val port: Int = server.localPort

        init {
            thread.isDaemon = true
            thread.name = "w6-23-loopback-peer"
            thread.start()
        }

        fun await(timeoutMs: Long = 5_000) {
            thread.join(timeoutMs)
            if (thread.isAlive) throw AssertionError("loopback peer did not finish within " + timeoutMs + " ms")
            failure.get()?.let { throw AssertionError("loopback peer failed", it) }
        }

        fun closeQuietly() {
            runCatching { server.close() }
        }
    }

    /** 内存版 SourceSyncStore：只够导入服务用，记 create/update/list 调用数。 */
    private class ImportFakeStore : SourceSyncStore {
        val snapshots = LinkedHashMap<String, SourceSyncSourceSnapshot>()

        var listCalls = 0
            private set

        var createCalls = 0
            private set

        var updateCalls = 0
            private set

        private var sequence = 0

        override suspend fun listSourceSnapshots(): List<SourceSyncSourceSnapshot> = snapshots.values.toList()

        override suspend fun listLocalSources(): List<LocalSourceRecord> {
            listCalls++
            return snapshots.values.map { it.toLocalRecord() }
        }

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
        const val HOST = "127.0.0.1"
        const val DEVICE_ID = "device-b"
        const val CHALLENGE = "w6-23-loopback-challenge"
        const val GOLDEN_RESOURCE = "sync/windows-envelope-device-a.json"
        const val GOLDEN_SHA256 = "049688090c5e8dd2f5d79b69f8375cc4c37d54f3ac15abd738861139f42334c8"
    }
}
