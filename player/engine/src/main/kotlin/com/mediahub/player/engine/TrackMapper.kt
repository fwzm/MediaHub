package com.mediahub.player.engine

import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleTrack

/**
 * Media3 轨道信息 → 领域模型。
 *
 * [snapshotToken] 标识本次映射所用的轨道快照；UI 构造的 [TrackSelection] 携带该 token，
 * 引擎在应用前比对当前 token，避免旧快照的行回调误选新快照中的同号轨道。
 */
data class MappedTracks(
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedAudio: TrackSelection? = null,
    val selectedSubtitle: TrackSelection? = null,
    val snapshotToken: Long = 0L,
    val rowMap: TrackRowMap = TrackRowMap.EMPTY,
)

/**
 * 轨道映射。
 *
 * **逐轨展开**：遍历每个音频 / 文本 [Tracks.Group] 内的**全部**轨道（不再只取 `getTrackFormat(0)`），
 * 逐轨读取 format、支持状态、默认标记与选中态。同组多轨（例如多语言音轨打包在一个组里）因此
 * 不会遗漏，也不会把同组第一轨误标为选中。
 *
 * **三层序号语义**（ADR-032 勘误）：
 * - [AudioTrack.index] / [SubtitleTrack.index]：**列表行序号**（0..N-1），仅表示 UI 列表位置；
 * - [TrackSelection.groupIndex]：`MappedTrackInfo.getTrackGroups(rendererIndex)` 的**同类型内组序号**；
 * - [TrackSelection.trackIndex]：组内轨序号。
 *
 * 行序号与组 / 轨地址的对应关系由 [MappedTracks.rowMap] 同步产出，UI 不再把行序号当组号。
 */
object TrackMapper {

    /** 轨道快照单调递增令牌：每次 mapTracks 生成新 token，使旧地址失效。 */
    private val snapshotCounter = java.util.concurrent.atomic.AtomicLong(0L)

    fun mapTracks(tracks: Tracks): MappedTracks {
        val audio = mutableListOf<AudioTrack>()
        val subtitles = mutableListOf<SubtitleTrack>()
        val audioRowMap = mutableMapOf<Int, TrackSelection>()
        val subtitleRowMap = mutableMapOf<Int, TrackSelection>()
        var selectedAudio: TrackSelection? = null
        var selectedSubtitle: TrackSelection? = null
        val token = snapshotCounter.incrementAndGet()

        var audioGroupOrdinal = 0
        var textGroupOrdinal = 0

        tracks.groups.forEach { group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    val groupIndex = audioGroupOrdinal
                    audioGroupOrdinal += 1
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val supported = group.isTrackSupported(trackIndex)
                        val selected = isTrackSelected(group, trackIndex)
                        val rowIndex = audio.size
                        val address = TrackSelection(groupIndex, trackIndex, token)
                        audioRowMap[rowIndex] = address
                        audio += AudioTrack(
                            index = rowIndex,
                            language = format.language,
                            title = format.label ?: format.id,
                            codec = format.sampleMimeType,
                            channels = format.channelCount,
                            sampleRate = format.sampleRate,
                            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                            isSelected = selected,
                            isSupported = supported,
                            decoderName = runCatching {
                                format.sampleMimeType?.let { mime ->
                                    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
                                    MediaCodecUtil.getDecoderInfo(mime, false, false)?.name
                                }
                            }.getOrNull(),
                        )
                        if (selected) selectedAudio = address
                    }
                }

                C.TRACK_TYPE_TEXT -> {
                    val groupIndex = textGroupOrdinal
                    textGroupOrdinal += 1
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val supported = group.isTrackSupported(trackIndex)
                        val selected = isTrackSelected(group, trackIndex)
                        val rowIndex = subtitles.size
                        val address = TrackSelection(groupIndex, trackIndex, token)
                        subtitleRowMap[rowIndex] = address
                        subtitles += SubtitleTrack(
                            index = rowIndex,
                            language = format.language,
                            title = format.label,
                            format = format.sampleMimeType,
                            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                            isSelected = selected,
                            isSupported = supported,
                        )
                        if (selected) selectedSubtitle = address
                    }
                }
            }
        }

        return MappedTracks(
            audioTracks = audio,
            subtitleTracks = subtitles,
            selectedAudio = selectedAudio,
            selectedSubtitle = selectedSubtitle,
            snapshotToken = token,
            rowMap = TrackRowMap(audioRowMap, subtitleRowMap, token),
        )
    }

    /** 组内第 trackIndex 轨是否当前选中：优先逐轨标记，退化为整组 isSelected（单轨组）。 */
    private fun isTrackSelected(group: Tracks.Group, trackIndex: Int): Boolean {
        val perTrack = runCatching { group.isTrackSelected(trackIndex) }.getOrNull()
        return perTrack ?: (group.isSelected && trackIndex == 0)
    }
}
