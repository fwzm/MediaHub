package com.mediahub.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mediahub.model.HdrType
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackMode
import com.mediahub.model.PlaybackSource
import com.mediahub.model.ProfessionalInfoPreferences
import com.mediahub.model.UserPreferences
import com.mediahub.player.engine.EngineKind
import com.mediahub.player.engine.PlaybackUiState

/**
 * 播放专业信息面板（P1 第一垂直切片）。
 *
 * 三段式数据契约——严格区分三类来源，绝不互相冒充：
 * 1. 源参数：本次 resolve 到的 [PlaybackSource]（PlaybackInfo 语义）+ 启动快照容器；
 * 2. 配置：[UserPreferences]（用户"选择了什么"，不代表实际生效，如硬解偏好）；
 * 3. 运行观测：[EngineKind] + [PlaybackUiState]（引擎本次实际输出，如实际分辨率/音频输出）。
 *
 * 未知一律输出 [ProfessionalInfoPreferences.UNKNOWN]（"—"）：
 * - 帧率：模型层无任何来源 → 恒为未知；
 * - 实际解码器：PlaybackUiState 未暴露解码器名 → 恒为未知（不伪造，待引擎侧补充观测）。
 *
 * [ProfessionalInfoPreferences.expertMode] 只控制密度：精简模式仅保留
 * `primary = true` 的关键行，三段结构与取值逻辑不变；不重建播放、不改内核。
 */

/** 面板单行：label=字段名，value=展示值（未知即 "—"），primary=精简模式仍保留的关键行。 */
data class PlayerInfoRow(
    val label: String,
    val value: String,
    val primary: Boolean = false,
)

/** 面板分节：源参数 / 配置 / 运行观测。 */
data class PlayerInfoSectionData(
    val title: String,
    val rows: List<PlayerInfoRow>,
)

/** 面板输入快照（三类数据在此显式分字段，避免调用方混装）。 */
data class PlayerInfoPanelInputs(
    val source: PlaybackSource?,
    val launchContainer: String?,
    val preferences: UserPreferences,
    val engineKind: EngineKind?,
    val uiState: PlaybackUiState,
)

/**
 * 面板文案。中文默认值与 values/strings.xml 一致；UI 侧经
 * [rememberPlayerInfoLabels] 用资源覆盖以支持 values-en。
 * 纯函数映射测试直接使用默认实例。
 */
data class PlayerInfoLabels(
    val unknown: String = "—",
    val sourceTitle: String = "源参数",
    val configTitle: String = "配置",
    val runtimeTitle: String = "运行观测",
    val container: String = "封装",
    val videoCodec: String = "视频编码",
    val audioCodec: String = "音频编码",
    val bitrate: String = "码率",
    val resolution: String = "分辨率",
    val frameRate: String = "帧率",
    val hdr: String = "HDR",
    val playbackMode: String = "播放方式",
    val engineMode: String = "内核选择",
    val hardwareDecoding: String = "解码设置（硬解偏好）",
    val preferDirectPlay: String = "优先直连",
    val bitrateCap: String = "码率上限",
    val actualEngine: String = "实际内核",
    val actualDecoder: String = "实际解码器",
    val actualResolution: String = "实际分辨率",
    val audioOutput: String = "音频输出",
    val audioTrackCount: String = "音轨",
    val subtitleTrackCount: String = "字幕",
    val on: String = "开",
    val off: String = "关",
    val unlimited: String = "不限",
    val modeDirectPlay: String = "直连播放",
    val modeDirectStream: String = "直流转码",
    val modeTranscode: String = "服务端转码",
    val modeUnsupported: String = "无法播放",
    val engineAuto: String = "自动（Media3→mpv）",
    val engineMedia3: String = "Media3",
    val engineMpv: String = "mpv",
    val hdrHdr10: String = "HDR10",
    val hdrHdr10Plus: String = "HDR10+",
    val hdrHlg: String = "HLG",
    val hdrDolbyVision: String = "Dolby Vision",
    val expertToggle: String = "专业信息",
) {
    fun playbackModeName(mode: PlaybackMode): String = when (mode) {
        PlaybackMode.DIRECT_PLAY -> modeDirectPlay
        PlaybackMode.DIRECT_STREAM -> modeDirectStream
        PlaybackMode.TRANSCODE -> modeTranscode
        PlaybackMode.UNSUPPORTED -> modeUnsupported
    }

    fun engineModeName(mode: PlaybackEngineMode): String = when (mode) {
        PlaybackEngineMode.AUTO -> engineAuto
        PlaybackEngineMode.MEDIA3 -> engineMedia3
        PlaybackEngineMode.MPV -> engineMpv
    }

    fun hdrName(type: HdrType): String = when (type) {
        // NONE=源未声明 HDR：写未知，不替源断言"SDR"。
        HdrType.NONE -> unknown
        HdrType.HDR10 -> hdrHdr10
        HdrType.HDR10_PLUS -> hdrHdr10Plus
        HdrType.HLG -> hdrHlg
        HdrType.DOLBY_VISION -> hdrDolbyVision
    }
}

@Composable
fun rememberPlayerInfoLabels(): PlayerInfoLabels = PlayerInfoLabels(
    unknown = stringResource(R.string.player_info_unknown),
    sourceTitle = stringResource(R.string.player_info_source),
    configTitle = stringResource(R.string.player_info_config),
    runtimeTitle = stringResource(R.string.player_info_runtime),
    container = stringResource(R.string.player_info_container),
    videoCodec = stringResource(R.string.player_info_video_codec),
    audioCodec = stringResource(R.string.player_info_audio_codec),
    bitrate = stringResource(R.string.player_info_bitrate),
    resolution = stringResource(R.string.player_info_resolution),
    frameRate = stringResource(R.string.player_info_frame_rate),
    hdr = stringResource(R.string.player_info_hdr),
    playbackMode = stringResource(R.string.player_info_playback_mode),
    engineMode = stringResource(R.string.player_info_engine_mode),
    hardwareDecoding = stringResource(R.string.player_info_hardware_decoding),
    preferDirectPlay = stringResource(R.string.player_info_prefer_direct_play),
    bitrateCap = stringResource(R.string.player_info_bitrate_cap),
    actualEngine = stringResource(R.string.player_info_actual_engine),
    actualDecoder = stringResource(R.string.player_info_actual_decoder),
    actualResolution = stringResource(R.string.player_info_actual_resolution),
    audioOutput = stringResource(R.string.player_info_audio_output),
    audioTrackCount = stringResource(R.string.player_info_audio_track_count),
    subtitleTrackCount = stringResource(R.string.player_info_subtitle_track_count),
    on = stringResource(R.string.player_info_on),
    off = stringResource(R.string.player_info_off),
    unlimited = stringResource(R.string.player_info_unlimited),
    modeDirectPlay = stringResource(R.string.player_info_mode_direct_play),
    modeDirectStream = stringResource(R.string.player_info_mode_direct_stream),
    modeTranscode = stringResource(R.string.player_info_mode_transcode),
    modeUnsupported = stringResource(R.string.player_info_mode_unsupported),
    engineAuto = stringResource(R.string.player_info_engine_auto),
    engineMedia3 = stringResource(R.string.player_info_engine_media3),
    engineMpv = stringResource(R.string.player_info_engine_mpv),
    hdrHdr10 = stringResource(R.string.player_info_hdr10),
    hdrHdr10Plus = stringResource(R.string.player_info_hdr10_plus),
    hdrHlg = stringResource(R.string.player_info_hlg),
    hdrDolbyVision = stringResource(R.string.player_info_dolby_vision),
    expertToggle = stringResource(R.string.player_info_expert_toggle),
)

/** 码率展示：null/非正值=未知；≥1 Mbps 用 Mbps（1 位小数），否则 kbps。 */
fun formatPlayerInfoBitrate(bps: Long?, unknown: String): String = when {
    bps == null || bps <= 0 -> unknown
    bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
    else -> "%.0f kbps".format(bps / 1_000.0)
}

/** 分辨率展示：宽高都 >0 才输出 "W×H"，否则未知。 */
fun formatPlayerInfoResolution(width: Int?, height: Int?, unknown: String): String =
    if (width != null && height != null && width > 0 && height > 0) "${width}×${height}" else unknown

/**
 * 纯函数映射：三类输入 → 三段渲染模型。
 * 不读引擎、不发请求；缺失字段落 [ProfessionalInfoPreferences.UNKNOWN]，绝不编造。
 */
fun buildPlayerInfoSections(
    inputs: PlayerInfoPanelInputs,
    labels: PlayerInfoLabels,
): List<PlayerInfoSectionData> {
    val unknown = labels.unknown
    val source = inputs.source
    val prefs = inputs.preferences
    val ui = inputs.uiState

    val sourceSection = PlayerInfoSectionData(
        title = labels.sourceTitle,
        rows = listOf(
            PlayerInfoRow(
                label = labels.container,
                value = source?.container ?: inputs.launchContainer?.takeIf { it.isNotBlank() } ?: unknown,
                primary = true,
            ),
            PlayerInfoRow(label = labels.videoCodec, value = source?.videoCodec ?: unknown, primary = true),
            PlayerInfoRow(label = labels.audioCodec, value = source?.audioCodec ?: unknown),
            PlayerInfoRow(label = labels.bitrate, value = formatPlayerInfoBitrate(source?.bitrate, unknown)),
            PlayerInfoRow(
                label = labels.resolution,
                value = formatPlayerInfoResolution(source?.width, source?.height, unknown),
                primary = true,
            ),
            // 模型层（PlaybackSource/MediaStream/MediaItem）均无帧率字段：恒为未知。
            PlayerInfoRow(label = labels.frameRate, value = unknown),
            PlayerInfoRow(label = labels.hdr, value = labels.hdrName(source?.hdrType ?: HdrType.NONE)),
            PlayerInfoRow(
                label = labels.playbackMode,
                value = source?.let { labels.playbackModeName(it.mode) } ?: unknown,
            ),
        ),
    )

    val configSection = PlayerInfoSectionData(
        title = labels.configTitle,
        rows = listOf(
            PlayerInfoRow(
                label = labels.engineMode,
                value = labels.engineModeName(prefs.playbackEngineMode),
                primary = true,
            ),
            // 偏好≠实际：这里只陈述用户设置，不推断实际是否硬解。
            PlayerInfoRow(
                label = labels.hardwareDecoding,
                value = if (prefs.enableHardwareDecoding) labels.on else labels.off,
                primary = true,
            ),
            PlayerInfoRow(
                label = labels.preferDirectPlay,
                value = if (prefs.preferDirectPlay) labels.on else labels.off,
            ),
            PlayerInfoRow(
                label = labels.bitrateCap,
                value = prefs.maxBitrateBps?.let { formatPlayerInfoBitrate(it, unknown) } ?: labels.unlimited,
            ),
        ),
    )

    val runtimeSection = PlayerInfoSectionData(
        title = labels.runtimeTitle,
        rows = listOf(
            PlayerInfoRow(
                label = labels.actualEngine,
                value = inputs.engineKind?.name ?: unknown,
                primary = true,
            ),
            // PlaybackUiState 未暴露实际解码器名：写未知，不伪造（后续引擎侧观测补齐）。
            PlayerInfoRow(label = labels.actualDecoder, value = unknown),
            PlayerInfoRow(
                label = labels.actualResolution,
                value = formatPlayerInfoResolution(ui.videoWidth, ui.videoHeight, unknown),
                primary = true,
            ),
            PlayerInfoRow(
                label = labels.audioOutput,
                value = ui.audioFormatMime?.let { prettyCodecName(it) ?: it } ?: unknown,
            ),
            PlayerInfoRow(label = labels.audioTrackCount, value = ui.audioTracks.size.toString()),
            PlayerInfoRow(label = labels.subtitleTrackCount, value = ui.subtitleTracks.size.toString()),
        ),
    )

    return if (prefs.professionalInfo.expertMode) {
        listOf(sourceSection, configSection, runtimeSection)
    } else {
        listOf(sourceSection, configSection, runtimeSection).map { section ->
            section.copy(rows = section.rows.filter { it.primary })
        }
    }
}

/** 播放信息 Bottom Sheet：顶部快速专业/精简切换（只改密度），下方三段信息。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerInfoPanelSheet(
    inputs: PlayerInfoPanelInputs,
    onExpertModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val labels = rememberPlayerInfoLabels()
    val sections = buildPlayerInfoSections(inputs, labels)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    labels.expertToggle,
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(
                    checked = inputs.preferences.professionalInfo.expertMode,
                    onCheckedChange = onExpertModeChange,
                )
            }
            sections.forEachIndexed { index, section ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                section.rows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            row.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
