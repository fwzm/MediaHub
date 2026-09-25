package com.mediahub.feature.player

import com.mediahub.model.AudioTrack
import com.mediahub.model.HdrType
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackSource
import com.mediahub.model.ProfessionalInfoPreferences
import com.mediahub.model.SubtitleTrack
import com.mediahub.model.UserPreferences
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.PlaybackUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专业信息面板三段映射测试（纯函数，不触 Compose / 引擎）：
 * 给定源参数 + 配置 + 运行观测 → 三段渲染模型；未知字段必须落 "—"，绝不编造；
 * 配置（用户设置）与运行观测（引擎实际）严格分离；expertMode 只控制密度。
 */
class PlayerInfoPanelStateTest {

    private val labels = PlayerInfoLabels()

    private fun fullSource() = PlaybackSource(
        url = "http://media/stream.mkv",
        container = "mkv",
        videoCodec = "h264",
        audioCodec = "truehd",
        bitrate = 8_000_000L,
        width = 1920,
        height = 1080,
        hdrType = HdrType.DOLBY_VISION,
        mode = PlaybackMode.DIRECT_PLAY,
    )

    private fun fullUiState() = PlaybackUiState(
        videoWidth = 1920,
        videoHeight = 1080,
        audioTracks = listOf(
            AudioTrack(index = 0, codec = "truehd", channels = 8),
            AudioTrack(index = 1, codec = "aac", channels = 2),
        ),
        subtitleTracks = listOf(SubtitleTrack(index = 0, format = "srt")),
        audioFormatMime = "audio/mp4a-latm",
    )

    private fun rowsByLabel(sections: List<PlayerInfoSectionData>): Map<Int, Map<String, PlayerInfoRow>> =
        sections.withIndex().associate { (index, section) ->
            index to section.rows.associateBy { it.label }
        }

    @Test
    fun `expert inputs map into three sections with source config and runtime values`() {
        val sections = buildPlayerInfoSections(
            inputs = PlayerInfoPanelInputs(
                source = fullSource(),
                launchContainer = "mov",
                preferences = UserPreferences(
                    playbackEngineMode = PlaybackEngineMode.MPV,
                    enableHardwareDecoding = true,
                    preferDirectPlay = false,
                    maxBitrateBps = 20_000_000L,
                ),
                engineKind = EngineKind.MEDIA3,
                uiState = fullUiState(),
            ),
            labels = labels,
        )

        assertEquals(listOf(labels.sourceTitle, labels.configTitle, labels.runtimeTitle), sections.map { it.title })
        val rows = rowsByLabel(sections)

        // 源参数（PlaybackSource / 启动快照）
        val source = rows.getValue(0)
        assertEquals("mkv", source.getValue(labels.container).value)
        assertEquals("h264", source.getValue(labels.videoCodec).value)
        assertEquals("truehd", source.getValue(labels.audioCodec).value)
        assertEquals("8.0 Mbps", source.getValue(labels.bitrate).value)
        assertEquals("1920×1080", source.getValue(labels.resolution).value)
        assertEquals("Dolby Vision", source.getValue(labels.hdr).value)
        assertEquals(labels.modeDirectPlay, source.getValue(labels.playbackMode).value)
        // 帧率无任何模型层来源：恒为未知，不编造
        assertEquals(ProfessionalInfoPreferences.UNKNOWN, source.getValue(labels.frameRate).value)

        // 配置（用户设置；与实际严格分离）
        val config = rows.getValue(1)
        assertEquals(labels.engineMpv, config.getValue(labels.engineMode).value)
        assertEquals(labels.on, config.getValue(labels.hardwareDecoding).value)
        assertEquals(labels.off, config.getValue(labels.preferDirectPlay).value)
        assertEquals("20.0 Mbps", config.getValue(labels.bitrateCap).value)

        // 运行观测（引擎实际输出）
        val runtime = rows.getValue(2)
        assertEquals("MEDIA3", runtime.getValue(labels.actualEngine).value)
        assertEquals("1920×1080", runtime.getValue(labels.actualResolution).value)
        assertEquals("AAC", runtime.getValue(labels.audioOutput).value)
        assertEquals("2", runtime.getValue(labels.audioTrackCount).value)
        assertEquals("1", runtime.getValue(labels.subtitleTrackCount).value)
        // 引擎未暴露实际解码器：恒为未知，不伪造
        assertEquals(ProfessionalInfoPreferences.UNKNOWN, runtime.getValue(labels.actualDecoder).value)
    }

    @Test
    fun `missing source and silent engine render unknown without fabrication`() {
        val sections = buildPlayerInfoSections(
            inputs = PlayerInfoPanelInputs(
                source = null,
                launchContainer = null,
                preferences = UserPreferences(),
                engineKind = null,
                uiState = PlaybackUiState(),
            ),
            labels = labels,
        )

        val rows = rowsByLabel(sections)
        val source = rows.getValue(0)
        listOf(
            labels.container,
            labels.videoCodec,
            labels.audioCodec,
            labels.bitrate,
            labels.resolution,
            labels.frameRate,
            labels.hdr,
            labels.playbackMode,
        ).forEach { label ->
            assertEquals("source row $label", ProfessionalInfoPreferences.UNKNOWN, source.getValue(label).value)
        }

        val runtime = rows.getValue(2)
        assertEquals(ProfessionalInfoPreferences.UNKNOWN, runtime.getValue(labels.actualEngine).value)
        assertEquals(ProfessionalInfoPreferences.UNKNOWN, runtime.getValue(labels.actualResolution).value)
        assertEquals(ProfessionalInfoPreferences.UNKNOWN, runtime.getValue(labels.audioOutput).value)

        // 配置是用户设置，始终可得（默认偏好），不因引擎未就绪变未知
        val config = rows.getValue(1)
        assertEquals(labels.engineAuto, config.getValue(labels.engineMode).value)
        assertEquals(labels.on, config.getValue(labels.hardwareDecoding).value)
        assertEquals(labels.unlimited, config.getValue(labels.bitrateCap).value)
    }

    @Test
    fun `launch snapshot container is a fallback only when source is missing`() {
        val withSnapshot = buildPlayerInfoSections(
            inputs = PlayerInfoPanelInputs(
                source = null,
                launchContainer = "mkv",
                preferences = UserPreferences(),
                engineKind = EngineKind.MPV,
                uiState = PlaybackUiState(),
            ),
            labels = labels,
        )
        assertEquals(
            "mkv",
            rowsByLabel(withSnapshot).getValue(0).getValue(labels.container).value,
        )

        val sourceWins = buildPlayerInfoSections(
            inputs = PlayerInfoPanelInputs(
                source = fullSource().copy(container = "mp4"),
                launchContainer = "mkv",
                preferences = UserPreferences(),
                engineKind = EngineKind.MPV,
                uiState = PlaybackUiState(),
            ),
            labels = labels,
        )
        assertEquals(
            "mp4",
            rowsByLabel(sourceWins).getValue(0).getValue(labels.container).value,
        )
    }

    @Test
    fun `simple mode keeps only primary rows with identical values`() {
        val inputs = PlayerInfoPanelInputs(
            source = fullSource(),
            launchContainer = "mov",
            preferences = UserPreferences(playbackEngineMode = PlaybackEngineMode.MEDIA3),
            engineKind = EngineKind.MEDIA3,
            uiState = fullUiState(),
        )
        val expert = buildPlayerInfoSections(inputs, labels)
        val simple = buildPlayerInfoSections(
            inputs.copy(
                preferences = inputs.preferences.copy(
                    professionalInfo = inputs.preferences.professionalInfo.copy(expertMode = false),
                ),
            ),
            labels,
        )

        // 三段结构不变；行是专家模式的严格子集；共享行取值逐字相同（只改密度，不改数据）
        assertEquals(expert.map { it.title }, simple.map { it.title })
        expert.zip(simple).forEach { (expertSection, simpleSection) ->
            val expertRows = expertSection.rows
            val simpleRows = simpleSection.rows
            assertTrue(simpleRows.size < expertRows.size)
            assertTrue(simpleRows.all { it.primary })
            assertTrue(expertRows.containsAll(simpleRows))
        }
    }

    @Test
    fun `user preference never masquerades as runtime observation`() {
        // 设置选了 MPV + 硬解开，但本次实际内核是 MEDIA3、解码器未知：
        // 配置段如实写偏好，运行段如实写观测，二者互不污染。
        val sections = buildPlayerInfoSections(
            inputs = PlayerInfoPanelInputs(
                source = fullSource(),
                launchContainer = null,
                preferences = UserPreferences(
                    playbackEngineMode = PlaybackEngineMode.MPV,
                    enableHardwareDecoding = true,
                ),
                engineKind = EngineKind.MEDIA3,
                uiState = PlaybackUiState(videoWidth = 1280, videoHeight = 720),
            ),
            labels = labels,
        )
        val rows = rowsByLabel(sections)

        assertEquals(labels.engineMpv, rows.getValue(1).getValue(labels.engineMode).value)
        assertEquals(labels.on, rows.getValue(1).getValue(labels.hardwareDecoding).value)
        assertEquals("MEDIA3", rows.getValue(2).getValue(labels.actualEngine).value)
        assertEquals(ProfessionalInfoPreferences.UNKNOWN, rows.getValue(2).getValue(labels.actualDecoder).value)
        // 运行分辨率用引擎观测（1280×720），不用源声明（1920×1080）
        assertEquals("1280×720", rows.getValue(2).getValue(labels.actualResolution).value)
        assertEquals("1920×1080", rows.getValue(0).getValue(labels.resolution).value)
    }

    @Test
    fun `bitrate formatter only claims known positive values`() {
        val unknown = labels.unknown
        assertEquals(unknown, formatPlayerInfoBitrate(null, unknown))
        assertEquals(unknown, formatPlayerInfoBitrate(0L, unknown))
        assertEquals(unknown, formatPlayerInfoBitrate(-5L, unknown))
        assertEquals("8.0 Mbps", formatPlayerInfoBitrate(8_000_000L, unknown))
        assertEquals("800 kbps", formatPlayerInfoBitrate(800_000L, unknown))
    }

    @Test
    fun `resolution formatter requires positive width and height`() {
        val unknown = labels.unknown
        assertEquals(unknown, formatPlayerInfoResolution(null, null, unknown))
        assertEquals(unknown, formatPlayerInfoResolution(1920, 0, unknown))
        assertEquals(unknown, formatPlayerInfoResolution(0, 1080, unknown))
        assertEquals("1920×1080", formatPlayerInfoResolution(1920, 1080, unknown))
    }
}
