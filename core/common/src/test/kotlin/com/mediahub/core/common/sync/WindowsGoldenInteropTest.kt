package com.mediahub.core.common.sync

import com.mediahub.model.ServerType
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与 Windows 真实产物的互操作对照（不是手写夹具）。
 *
 * 三个资源全部由 D:\mh-w6 的 MediaHub.SyncCli.exe 真跑导出：
 * - windows-envelope-device-a.json：device-a 的 export 信封（3 源 + 1 墓碑，含一个未知 sourceType）
 * - windows-plan-device-b-empty.json：device-b 空目录时对该信封的 plan（counts 2/1/1）
 * - windows-plan-device-b-applied.json：device-b import --apply 之后再次 plan（counts 0/1/3）
 *
 * 对照口径：Windows CLI 的 reason 是"轮级"码（apply-remote-change / unknown-source-type /
 * tombstone-unknown / already-applied），与 SourceSyncPlanner 的来源级 reason 不是同一套，
 * 因此只能比桶（apply / requiresApproval / ignored）；本类显式记录这个映射。
 */
class WindowsGoldenInteropTest {

    private val goldenEnvelope = "windows-envelope-device-a.json"
    private val goldenPlanEmpty = "windows-plan-device-b-empty.json"
    private val goldenPlanApplied = "windows-plan-device-b-applied.json"

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/sync/" + name)) { "缺少测试资源 /sync/" + name }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private data class CliCounts(val apply: Int, val requiresApproval: Int, val ignored: Int)

    private fun cliCounts(name: String): CliCounts {
        val counts = Json.parseToJsonElement(resource(name)).jsonObject.getValue("counts").jsonObject
        return CliCounts(
            apply = counts.getValue("apply").jsonPrimitive.int,
            requiresApproval = counts.getValue("requiresApproval").jsonPrimitive.int,
            ignored = counts.getValue("ignored").jsonPrimitive.int,
        )
    }

    private fun cliReasons(name: String, bucket: String): List<String> {
        val root = Json.parseToJsonElement(resource(name)).jsonObject
        val array: JsonArray = root.getValue(bucket).jsonArray
        return array.map { it.jsonObject.getValue("reason").jsonPrimitive.contentOrNull ?: "" }
    }

    // ------------------------------------------------------------------ (a) 解析不丢字段

    @Test
    fun golden_envelope_parses_without_losing_envelope_fields() {
        val envelope = SourceSyncCodec.decode(resource(goldenEnvelope))
        assertEquals(1, envelope.schemaVersion)
        assertEquals(1, envelope.minimumReaderVersion)
        assertEquals("device-a", envelope.deviceId)
        assertEquals("1b96e7eb417e4caeb27eb60f3d1acfc3", envelope.snapshotId)
        assertEquals(1792784739843L, envelope.createdAtEpochMs)
        assertFalse(envelope.capabilities.canSendSecrets)
        // 未知 sourceType（quantum-nas）必须留在能力声明里，不得被过滤。
        assertEquals(listOf("emby", "jellyfin", "quantum-nas"), envelope.capabilities.sourceTypes)
    }

    @Test
    fun golden_sources_keep_identity_endpoints_and_unknown_source_type() {
        val envelope = SourceSyncCodec.decode(resource(goldenEnvelope))
        assertEquals(3, envelope.sources.size)

        val emby = envelope.sources[0]
        assertEquals("local-385c7c48494fcb81", emby.syncSourceId)
        assertEquals("emby", emby.sourceType.raw)
        assertEquals("客厅 Emby", emby.displayName)
        assertEquals("alice", emby.accountScope)
        assertEquals(3L, emby.recordRevision)
        assertEquals(0, emby.fieldVersion)
        assertNull(emby.secrets)
        val embyEndpoint = emby.endpoints.single()
        assertEquals("http://nas-a:8096/emby", embyEndpoint.url)
        assertTrue(embyEndpoint.isPrimary)
        assertTrue(embyEndpoint.enabled)
        assertEquals(0, embyEndpoint.sortOrder)

        val jellyfin = envelope.sources[1]
        assertEquals("local-0a87ea9520c51a80", jellyfin.syncSourceId)
        assertEquals("jellyfin", jellyfin.sourceType.wire)
        assertEquals("Jellyfin 楼上", jellyfin.displayName)
        assertEquals("bob", jellyfin.accountScope)
        assertEquals("http://nas-a:8096/jelly", jellyfin.endpoints.single().url)

        val unknown = envelope.sources[2]
        assertEquals("local-76e5f67669136fae", unknown.syncSourceId)
        assertEquals("quantum-nas", unknown.sourceType.raw)
        assertFalse(unknown.sourceType.isKnown)
        assertEquals("Quantum NAS", unknown.displayName)
        assertNull(unknown.accountScope)
        assertEquals("https://quantum.example:9443/v1", unknown.endpoints.single().url)
        assertEquals(2L, unknown.recordRevision)
    }

    @Test
    fun golden_tombstone_keeps_identity_timestamp_and_revision() {
        val envelope = SourceSyncCodec.decode(resource(goldenEnvelope))
        val tombstone = envelope.deletions.single()
        assertEquals("local-f7ebca3dbf01eb17", tombstone.syncSourceId)
        assertEquals(1760000000000L, tombstone.deletedAtEpochMs)
        assertEquals(5L, tombstone.recordRevision)
    }

    // ------------------------------------------------------------------ (c) 回喂 Windows

    @Test
    fun kotlin_re_encoding_is_structurally_identical_to_the_windows_envelope() {
        val decoded = SourceSyncCodec.decode(resource(goldenEnvelope))
        val encoded = SourceSyncCodec.encode(decoded)
        assertEquals(
            Json.parseToJsonElement(resource(goldenEnvelope)),
            Json.parseToJsonElement(encoded),
        )
    }

    @Test
    fun kotlin_envelope_is_utf8_without_escapes_and_written_for_the_cli() {
        val encoded = SourceSyncCodec.encode(SourceSyncCodec.decode(resource(goldenEnvelope)))
        // Windows CLI 把非 ASCII 写成 \uXXXX；Kotlin 侧不转义，二者只能结构化比较（见上一个用例）。
        assertTrue("应保留原始中文，实际 = " + encoded, encoded.contains("客厅 Emby"))
        assertFalse("不得出现 \\u 转义，实际 = " + encoded, encoded.contains("\\u"))

        val outDir = File("build/sync-interop")
        outDir.mkdirs()
        val outFile = File(outDir, "kotlin-envelope.json")
        outFile.writeText(encoded, Charsets.UTF_8)
        assertTrue(outFile.isFile)
        assertTrue(outFile.length() > 0L)
        println("[interop-c] kotlin envelope => " + outFile.absolutePath + " (" + outFile.length() + " bytes)")
    }

    @Test
    fun decoded_golden_survives_an_encode_decode_round_trip() {
        val decoded = SourceSyncCodec.decode(resource(goldenEnvelope))
        val again = SourceSyncCodec.decode(SourceSyncCodec.encode(decoded))
        assertEquals(decoded, again)
    }

    // ------------------------------------------------------------------ (b) 三桶对照

    @Test
    fun empty_device_plan_matches_the_windows_cli_buckets() {
        val envelope = SourceSyncCodec.decode(resource(goldenEnvelope))
        val summary = SourceSyncSummary.fromPlan(SourceSyncPlanner.plan(envelope, emptyList()))
        val cli = cliCounts(goldenPlanEmpty)

        assertEquals(2, summary.apply)
        assertEquals(1, summary.ignored)
        assertEquals(1, summary.unsupported)
        assertEquals(cli, CliCounts(summary.apply, summary.unsupported, summary.ignored))
        assertTrue(summary.hasChanges)
    }

    @Test
    fun empty_device_plan_classifies_each_source_like_the_windows_cli() {
        val envelope = SourceSyncCodec.decode(resource(goldenEnvelope))
        val plan = SourceSyncPlanner.plan(envelope, emptyList())
        assertEquals(4, plan.items.size)

        val emby = plan.items[0]
        assertEquals(SourceSyncAction.CREATE, emby.action)
        assertEquals(SourceSyncReasons.NEW_SOURCE, emby.reason)

        val jellyfin = plan.items[1]
        assertEquals(SourceSyncAction.CREATE, jellyfin.action)
        assertEquals(SourceSyncReasons.NEW_SOURCE, jellyfin.reason)

        val unknown = plan.items[2]
        assertEquals(SourceSyncAction.UNSUPPORTED, unknown.action)
        assertEquals(SourceSyncReasons.UNKNOWN_SOURCE_TYPE, unknown.reason)
        assertEquals("quantum-nas", unknown.sourceType)

        val tombstone = plan.items[3]
        assertEquals(SourceSyncAction.NO_CHANGE, tombstone.action)
        assertEquals(SourceSyncReasons.NO_LOCAL_SOURCE, tombstone.reason)

        // 轮级码对照：CLI 的 apply 两条都是 apply-remote-change，approval 是 unknown-source-type，
        // ignored 是 tombstone-unknown。
        assertEquals(listOf("apply-remote-change", "apply-remote-change"), cliReasons(goldenPlanEmpty, "apply"))
        assertEquals(listOf("unknown-source-type"), cliReasons(goldenPlanEmpty, "requiresApproval"))
        assertEquals(listOf("tombstone-unknown"), cliReasons(goldenPlanEmpty, "ignored"))
    }

    @Test
    fun applied_device_plan_matches_the_windows_cli_buckets() {
        val envelope = SourceSyncCodec.decode(resource(goldenEnvelope))
        val locals = listOf(
            LocalSourceRecord(
                serverId = "local-385c7c48494fcb81",
                syncSourceId = "local-385c7c48494fcb81",
                type = ServerType.EMBY,
                url = "http://nas-a:8096/emby",
                accountScope = "alice",
                displayName = "客厅 Emby",
                recordRevision = 3L,
            ),
            LocalSourceRecord(
                serverId = "local-0a87ea9520c51a80",
                syncSourceId = "local-0a87ea9520c51a80",
                type = ServerType.JELLYFIN,
                url = "http://nas-a:8096/jelly",
                accountScope = "bob",
                displayName = "Jellyfin 楼上",
                recordRevision = 1L,
            ),
        )
        val summary = SourceSyncSummary.fromPlan(SourceSyncPlanner.plan(envelope, locals))
        val cli = cliCounts(goldenPlanApplied)

        assertEquals(0, summary.apply)
        assertEquals(3, summary.ignored)
        assertEquals(1, summary.unsupported)
        assertEquals(cli, CliCounts(summary.apply, summary.unsupported, summary.ignored))
        assertFalse(summary.hasChanges)
        // 已应用的源在两端口径里都是"无需动作"，但牌面不同：CLI 说 already-applied，Planner 说 up-to-date。
        assertEquals(listOf("already-applied", "already-applied", "tombstone-unknown"), cliReasons(goldenPlanApplied, "ignored"))
    }

    @Test
    fun applied_device_plan_reports_up_to_date_for_both_adopted_sources() {
        val envelope = SourceSyncCodec.decode(resource(goldenEnvelope))
        val locals = listOf(
            LocalSourceRecord(
                serverId = "srv-emby",
                syncSourceId = "local-385c7c48494fcb81",
                type = ServerType.EMBY,
                url = "http://NAS-A:8096/emby/",
                accountScope = "alice",
                displayName = "客厅 Emby",
                recordRevision = 3L,
            ),
        )
        val plan = SourceSyncPlanner.plan(envelope, locals)
        val emby = plan.items.first { it.syncSourceId == "local-385c7c48494fcb81" }
        assertEquals(SourceSyncAction.NO_CHANGE, emby.action)
        assertEquals(SourceSyncReasons.UP_TO_DATE, emby.reason)
        assertEquals("srv-emby", emby.serverId)
    }
}
