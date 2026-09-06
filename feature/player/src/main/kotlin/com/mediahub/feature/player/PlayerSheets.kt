package com.mediahub.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleStyle
import com.mediahub.model.SubtitleTrack

/**
 * 播放器 Bottom Sheets（Phase 1B-2.4）：
 * - 音轨：带 codec / 声道 / 采样率 / 解码器 / 支持状态诊断；
 * - 字幕：轨道 + 样式（默认透明背景，见 ADR-032）。
 */

// ---- 格式化助手 ----

/** MIME → 展示名。 */
fun prettyCodecName(mime: String?): String? = when (mime) {
    null -> null
    "audio/mp4a-latm", "audio/mp4a-latm" -> "AAC"
    "audio/mpeg" -> "MP3"
    "audio/ac-3" -> "AC3"
    "audio/eac3" -> "EAC3"
    "audio/eac3-joc" -> "EAC3 (Atmos)"
    "audio/true-hd" -> "TrueHD"
    "audio/vnd.dts" -> "DTS"
    "audio/vnd.dts.hd" -> "DTS-HD"
    "audio/opus" -> "Opus"
    "audio/flac" -> "FLAC"
    "audio/raw" -> "LPCM"
    "audio/vorbis" -> "Vorbis"
    "application/x-ssa", "text/x-ssa" -> "ASS/SSA"
    "application/x-subrip", "application/x-subrip" -> "SRT"
    "text/vtt" -> "VTT"
    "application/pgs" -> "PGS"
    "application/ttml+xml", "application/x-mp4-vtt" -> "TTML"
    else -> mime.removePrefix("audio/").removePrefix("application/").removePrefix("text/").uppercase()
}

@Composable
fun formatChannels(channels: Int?): String? = when (channels) {
    null, 0 -> null
    1 -> stringResource(R.string.player_audio_mono)
    2 -> stringResource(R.string.player_audio_stereo)
    else -> stringResource(R.string.player_audio_channels, channels)
}

@Composable
fun formatSampleRate(hz: Int?): String? = hz?.takeIf { it > 0 }?.let {
    stringResource(R.string.player_audio_sample_rate, it / 1000)
}

// ---- 音轨 Bottom Sheet ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTrackSheet(
    tracks: List<AudioTrack>,
    onDismiss: () -> Unit,
    onSelect: (AudioTrack) -> Unit,
) {
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
            Text(stringResource(R.string.player_audio_tracks), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            if (tracks.isEmpty()) {
                Text(stringResource(R.string.player_audio_tracks_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            tracks.forEach { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(track) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = track.isSelected, onClick = { onSelect(track) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            track.title ?: track.language?.let { langName(it) }
                                ?: stringResource(R.string.player_audio_track_number, track.index + 1),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val details = buildList {
                            track.language?.let { add(langName(it)) }
                            prettyCodecName(track.codec)?.let { add(it) }
                            formatChannels(track.channels)?.let { add(it) }
                            formatSampleRate(track.sampleRate)?.let { add(it) }
                            track.decoderName?.let { add(stringResource(R.string.player_audio_decoder, it)) }
                        }
                        if (details.isNotEmpty()) {
                            Text(
                                details.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!track.isSupported) {
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.player_track_unsupported)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun langName(code: String): String = when (code.lowercase().substringBefore('-')) {
    "zh" -> stringResource(R.string.player_language_chinese)
    "en" -> stringResource(R.string.player_language_english)
    "ja" -> stringResource(R.string.player_language_japanese)
    "ko" -> stringResource(R.string.player_language_korean)
    "yue" -> stringResource(R.string.player_language_cantonese)
    else -> code.uppercase()
}

// ---- 字幕 Bottom Sheet ----

private data class ColorOption(@StringRes val label: Int, val color: Int)

private val TEXT_COLORS = listOf(
    ColorOption(R.string.player_color_white, 0xFFFFFFFF.toInt()),
    ColorOption(R.string.player_color_yellow, 0xFFFFFF00.toInt()),
    ColorOption(R.string.player_color_cyan, 0xFF00FFFF.toInt()),
    ColorOption(R.string.player_color_green, 0xFF00FF00.toInt()),
    ColorOption(R.string.player_color_pink, 0xFFFF69B4.toInt()),
    ColorOption(R.string.player_color_black, 0xFF000000.toInt()),
)

private val BG_COLORS = listOf(
    ColorOption(R.string.player_color_transparent, 0x00000000),
    ColorOption(R.string.player_color_black_half, 0x80000000.toInt()),
    ColorOption(R.string.player_color_black, 0xFF000000.toInt()),
    ColorOption(R.string.player_color_white, 0xFFFFFFFF.toInt()),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSheet(
    tracks: List<SubtitleTrack>,
    style: SubtitleStyle,
    onDismiss: () -> Unit,
    onSelect: (SubtitleTrack?) -> Unit,
    onStyleChange: (SubtitleStyle) -> Unit,
) {
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.player_subtitles), style = MaterialTheme.typography.titleMedium)

            Text(stringResource(R.string.player_subtitle_tracks), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            SubtitleRow(label = stringResource(R.string.player_subtitles_off), selected = tracks.none { it.isSelected }, onClick = { onSelect(null) })
            tracks.forEach { track ->
                SubtitleRow(
                    label = buildString {
                        append(track.title ?: track.language?.let { langName(it) }
                            ?: stringResource(R.string.player_subtitle_track_number, track.index + 1))
                        prettyCodecName(track.format)?.let { append(" · $it") }
                    },
                    selected = track.isSelected,
                    onClick = { onSelect(track) },
                )
            }

            Text(stringResource(R.string.player_subtitle_style), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))

            Text(stringResource(R.string.player_subtitle_scale, style.textScale * 100), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = style.textScale,
                onValueChange = { onStyleChange(style.copy(textScale = it)) },
                valueRange = 0.6f..2.0f,
            )

            ColorRow(
                label = stringResource(R.string.player_subtitle_text_color),
                options = TEXT_COLORS,
                selected = style.textColor,
                onSelect = { onStyleChange(style.copy(textColor = it)) },
            )
            ColorRow(
                label = stringResource(R.string.player_subtitle_background),
                options = BG_COLORS,
                selected = style.backgroundColor,
                onSelect = { onStyleChange(style.copy(backgroundColor = it)) },
            )

            Text(stringResource(R.string.player_subtitle_edge), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    stringResource(R.string.player_subtitle_edge_none) to SubtitleStyle.EDGE_TYPE_NONE,
                    stringResource(R.string.player_subtitle_edge_outline) to SubtitleStyle.EDGE_TYPE_OUTLINE,
                    stringResource(R.string.player_subtitle_edge_shadow) to SubtitleStyle.EDGE_TYPE_DROP_SHADOW,
                ).forEach { (label, type) ->
                    AssistChip(
                        onClick = { onStyleChange(style.copy(edgeType = type)) },
                        label = {
                            Text(if (style.edgeType == type) stringResource(R.string.player_selected_option, label) else label)
                        },
                    )
                }
            }

            Text(stringResource(R.string.player_subtitle_position, style.bottomPaddingFraction * 100), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = style.bottomPaddingFraction,
                onValueChange = { onStyleChange(style.copy(bottomPaddingFraction = it)) },
                valueRange = 0.02f..0.35f,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.player_subtitle_embedded_style), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = style.applyEmbeddedStyles,
                    onCheckedChange = { onStyleChange(style.copy(applyEmbeddedStyles = it)) },
                )
            }
        }
    }
}

@Composable
private fun SubtitleRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ColorRow(
    label: String,
    options: List<ColorOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            options.forEach { option ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onSelect(option.color) },
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                Color(option.color).copy(alpha = if (option.color ushr 24 == 0) 0.05f else 1f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (option.color == selected) {
                            Text(
                                "✓",
                                color = when {
                                    option.color ushr 24 == 0 -> MaterialTheme.colorScheme.onSurface
                                    Color(option.color).luminance() > 0.5f -> Color.Black
                                    else -> Color.White
                                },
                            )
                        }
                    }
                    Text(stringResource(option.label), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
