package com.mediahub.model

/** One playable source line for the player route. */
data class PlaybackSourceOption(
    val serverId: String,
    val serverName: String,
    val itemId: String,
    val title: String,
    val label: String,
    val type: MediaType? = null,
    val runtimeMs: Long? = null,
    val posterUrl: String? = null,
    val container: String? = null,
) {
    val key: String get() = serverId + "\u0000" + itemId
}
