package com.mediahub.core.common.pairing

import com.mediahub.core.common.sync.SourceSyncAction
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncPlanner
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输层到规划层的端到端测试：假 Windows 主机发的是 W6-A 冻结的黄金信封（不是合成数据），
 * 收下来解码后必须规划成与 Windows CLI 相同的三桶 2 / 1 / 1。
 *
 * 这里刻意用同一条链路：socket 帧 -> PairingFrame 语义 -> SourceSyncCodec -> SourceSyncPlanner，
 * 任何一环的字段丢失都会让桶数变化，因此它同时是「帧搬运不丢字节」的证据。
 */
class PairingTransportEndToEndTest {

    @Test
    fun an_envelope_received_over_loopback_plans_to_the_same_buckets_as_the_cli() {
        val envelopeJson = resource("/sync/windows-envelope-device-a.json")
        val host = FakeWindowsPairingHost("envelope") { socket ->
            writeFrame(socket, envelopeJson.toByteArray(Charsets.UTF_8))
            awaitEndOfStream(socket)
        }
        try {
            val exchange = PairingTransportClient(2_000, 2_000).exchange("127.0.0.1", host.port)
            assertTrue("期望收到帧，实际 " + describe(exchange), exchange is PairingFrameResult.Ok)
            val json = String((exchange as PairingFrameResult.Ok).payload, Charsets.UTF_8)
            assertTrue("客户端读完应立即关闭 socket", host.awaitPeerClosed(2_000))

            val envelope = SourceSyncCodec.decode(json)
            val plan = SourceSyncPlanner.plan(envelope, emptyList())
            val apply = plan.items.count {
                it.action == SourceSyncAction.CREATE || it.action == SourceSyncAction.UPDATE
            }
            val requiresApproval = plan.items.count {
                it.requiresUserApproval || it.action == SourceSyncAction.UNSUPPORTED
            }
            val ignored = plan.items.count {
                it.action == SourceSyncAction.NO_CHANGE || it.action == SourceSyncAction.IGNORE_STALE
            }
            assertEquals(4, plan.items.size)
            assertEquals(2, apply)
            assertEquals(1, requiresApproval)
            assertEquals(1, ignored)

            val cli = cliCounts()
            assertEquals("apply 桶必须与 Windows CLI 一致", cli.apply, apply)
            assertEquals("requiresApproval 桶必须与 Windows CLI 一致", cli.requiresApproval, requiresApproval)
            assertEquals("ignored 桶必须与 Windows CLI 一致", cli.ignored, ignored)
        } finally {
            host.close()
        }
    }

    @Test
    fun the_envelope_fields_survive_the_frame_round_trip_verbatim() {
        val envelopeJson = resource("/sync/windows-envelope-device-a.json")
        val host = FakeWindowsPairingHost("fidelity") { socket ->
            writeFrame(socket, envelopeJson.toByteArray(Charsets.UTF_8))
            awaitEndOfStream(socket)
        }
        try {
            val exchange = PairingTransportClient(2_000, 2_000).exchange("127.0.0.1", host.port)
            assertTrue("期望收到帧，实际 " + describe(exchange), exchange is PairingFrameResult.Ok)
            val envelope = SourceSyncCodec.decode(
                String((exchange as PairingFrameResult.Ok).payload, Charsets.UTF_8),
            )
            assertEquals("device-a", envelope.deviceId)
            assertEquals("1b96e7eb417e4caeb27eb60f3d1acfc3", envelope.snapshotId)
            assertEquals(3, envelope.sources.size)
            assertEquals("local-385c7c48494fcb81", envelope.sources[0].syncSourceId)
            assertEquals("local-76e5f67669136fae", envelope.sources[2].syncSourceId)
            assertEquals("quantum-nas", envelope.sources[2].sourceType.raw)
            assertEquals(false, envelope.sources[2].sourceType.isKnown)
            assertEquals(1, envelope.deletions.size)
            assertEquals("local-f7ebca3dbf01eb17", envelope.deletions[0].syncSourceId)
            assertEquals(1_760_000_000_000L, envelope.deletions[0].deletedAtEpochMs)
            assertEquals(listOf("emby", "jellyfin", "quantum-nas"), envelope.capabilities.sourceTypes)
        } finally {
            host.close()
        }
    }

    @Test
    fun the_fake_host_leaves_no_listener_or_thread_behind() {
        val host = FakeWindowsPairingHost("cleanup") { socket ->
            writeFrame(socket, byteArrayOf(1))
            awaitEndOfStream(socket)
        }
        val port = host.port
        try {
            val exchange = PairingTransportClient(2_000, 2_000).exchange("127.0.0.1", port)
            assertTrue("期望收到帧，实际 " + describe(exchange), exchange is PairingFrameResult.Ok)
            assertTrue("客户端应关闭 socket", host.awaitPeerClosed(2_000))
        } finally {
            host.close()
        }
        assertTrue(host.isClosed)
        assertTrue("工作线程必须退出", !host.workerAlive)
        val reconnect = runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 300) }
        }
        assertTrue("端口应已释放，实际 " + reconnect.exceptionOrNull(), reconnect.isFailure)
        assertTrue(host.loopbackOnly)
    }

    private fun resource(path: String): String {
        val stream = javaClass.getResourceAsStream(path) ?: error("缺少测试资源 " + path)
        return stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
    }

    private fun cliCounts(): CliCounts {
        val text = resource("/sync/windows-plan-device-b-empty.json")
        val counts = Json.parseToJsonElement(text).jsonObject.getValue("counts").jsonObject
        return CliCounts(
            apply = counts.getValue("apply").jsonPrimitive.int,
            requiresApproval = counts.getValue("requiresApproval").jsonPrimitive.int,
            ignored = counts.getValue("ignored").jsonPrimitive.int,
        )
    }

    private fun describe(result: PairingFrameResult): String = when (result) {
        is PairingFrameResult.Ok -> "ok(" + result.payload.size + " 字节)"
        is PairingFrameResult.Failed -> result.reason
    }

    private data class CliCounts(val apply: Int, val requiresApproval: Int, val ignored: Int)
}
