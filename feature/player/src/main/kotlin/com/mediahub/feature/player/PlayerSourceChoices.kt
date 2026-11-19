package com.mediahub.feature.player

import com.mediahub.model.PlaybackSourceOption

data class PlayerSourceChoice(
    val option: PlaybackSourceOption,
    val label: String,
    val isCurrent: Boolean,
)

fun playerSourceChoices(
    options: List<PlaybackSourceOption>,
    currentServerId: String,
    currentItemId: String,
): List<PlayerSourceChoice> = options.map { option ->
    PlayerSourceChoice(
        option = option,
        label = option.label.ifBlank { option.serverName },
        isCurrent = option.serverId == currentServerId && option.itemId == currentItemId,
    )
}

/** A picker only makes sense when more than one server actually contributed a line. */
fun shouldShowPlayerSourceSelector(options: List<PlaybackSourceOption>): Boolean =
    options.map { it.serverId }.distinct().size >= 2
