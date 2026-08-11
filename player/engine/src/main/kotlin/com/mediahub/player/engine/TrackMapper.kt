package com.mediahub.player.engine

import androidx.media3.common.C
import androidx.media3.common.Tracks
import com.mediahub.model.AudioTrack
import com.mediahub.model.SubtitleTrack

/** Media3 轨道信息 → 领域模型。 */
data class MappedTracks(
    val audioTracks: List<AudioTrack> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedAudio: TrackSelection? = null,
    val selectedSubtitle: TrackSelection? = null,
)

object TrackMapper {

    fun mapTracks(tracks: Tracks): MappedTracks {
        val audio = mutableListOf<AudioTrack>()
        val subtitles = mutableListOf<SubtitleTrack>()
        var selectedAudio: TrackSelection? = null
        var selectedSubtitle: TrackSelection? = null

        tracks.groups.forEachIndexed { groupIndex, group ->
            val format = group.getTrackFormat(0)
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    audio += AudioTrack(
                        index = groupIndex,
                        language = format.language,
                        title = format.label ?: format.id,
                        codec = format.sampleMimeType,
                        channels = format.channelCount,
                        sampleRate = format.sampleRate,
                        isDefault = group.isSelected,
                    )
                    if (group.isSelected) selectedAudio = TrackSelection(groupIndex, 0)
                }

                C.TRACK_TYPE_TEXT -> {
                    subtitles += SubtitleTrack(
                        index = groupIndex,
                        language = format.language,
                        title = format.label,
                        format = format.sampleMimeType,
                        isDefault = group.isSelected,
                    )
                    if (group.isSelected) selectedSubtitle = TrackSelection(groupIndex, 0)
                }
            }
        }
        return MappedTracks(audio, subtitles, selectedAudio, selectedSubtitle)
    }
}
