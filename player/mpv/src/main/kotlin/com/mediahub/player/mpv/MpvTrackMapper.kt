package com.mediahub.player.mpv

import com.mediahub.model.AudioTrack
import com.mediahub.player.engine.TrackSelection

/**
 * mpv track-list → 统一 [AudioTrack] 映射（V15-A）。
 *
 * 背景（真机 P1）：mpv 引擎此前从不产出 audioTracks，导致双音轨 MKV 在「音轨」面板显示
 * 「未发现音轨」。Media3 的 TrackMapper 依赖 androidx.media3.common.Tracks，**不能**复用于 mpv，
 * 故此处建立独立映射。
 *
 * 语义约定：
 * - [AudioTrack.index] 使用**同类型内序号** 0..N-1（与 UI 列表位置、TrackSelection 一致）；
 * - mpv 的 track `id` 是 aid 取值，**不等于** FFmpeg stream index，也不等于列表位置；
 * - 缺失字段一律保留 null，**不伪造**语言/编码/声道/采样率。
 */
object MpvTrackMapper {

    /** 一行 track-list 快照所需属性（顺序见 [TRACK_PROPERTIES]）。 */
    val TRACK_PROPERTIES: List<String> = listOf("id", "type", "lang", "title", "codec", "selected")

    /**
     * 把 mpv 的 track-list 行映射为音频轨列表。
     *
     * @param trackList 每行是属性名→属性值（值可为 null，代表该属性不可读）
     */
    fun mapAudioTracks(trackList: List<Map<String, String?>>): List<AudioTrack> {
        val result = mutableListOf<AudioTrack>()
        for (row in trackList) {
            if (row["type"] != "audio") continue
            val selected = row["selected"] == "yes"
            result += AudioTrack(
                index = result.size,
                language = row["lang"]?.takeIf { it.isNotBlank() },
                title = row["title"]?.takeIf { it.isNotBlank() },
                codec = row["codec"]?.takeIf { it.isNotBlank() },
                channels = null,
                sampleRate = null,
                isDefault = selected,
                isSelected = selected,
                isSupported = true,
            )
        }
        return result
    }

    /**
     * 把 UI 的 [TrackSelection]（同类型内序号）解析为 mpv 的 aid（track id）。
     *
     * @return aid 字符串；null 表示应下发 `aid=no`；返回 "" 表示选择无效（不应下发）。
     */
    fun nativeAudioId(trackList: List<Map<String, String?>>, selection: TrackSelection?): String? {
        if (selection == null) return null
        return nativeAudioIds(trackList).getOrNull(selection.groupIndex)
    }

    /** 与 [mapAudioTracks] 输出一一对应的 mpv track id 列表。 */
    fun nativeAudioIds(trackList: List<Map<String, String?>>): List<String> =
        trackList.filter { it["type"] == "audio" }.mapNotNull { it["id"]?.takeIf { id -> id.isNotBlank() } }
}
