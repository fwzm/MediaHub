package com.mediahub.feature.detail.source

import com.mediahub.model.PlaybackSourceOption

/**
 * Maps canonical occurrences into player source lines.
 *
 * Labels follow the detail selector: one copy on a server keeps the server name, while
 * several copies on the same server are numbered in resolver order ("副本1", "副本2", ...).
 */
fun playbackSourceOptions(resolution: SourceResolution.Completed): List<PlaybackSourceOption> {
    val totals = resolution.occurrences.groupingBy { it.serverId }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return resolution.occurrences.map { occurrence ->
        val index = (seen[occurrence.serverId] ?: 0) + 1
        seen[occurrence.serverId] = index
        val label = if ((totals[occurrence.serverId] ?: 1) > 1) {
            occurrence.serverName + " · 副本" + index
        } else {
            occurrence.serverName
        }
        PlaybackSourceOption(
            serverId = occurrence.serverId,
            serverName = occurrence.serverName,
            itemId = occurrence.item.id,
            title = occurrence.item.title,
            label = label,
            type = occurrence.item.type,
            runtimeMs = occurrence.item.runtimeMs,
            posterUrl = occurrence.item.posterUrl,
            container = occurrence.item.container,
        )
    }
}
