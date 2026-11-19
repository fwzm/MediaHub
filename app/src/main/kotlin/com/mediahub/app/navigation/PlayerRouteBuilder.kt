package com.mediahub.app.navigation

import android.net.Uri
import com.mediahub.core.common.NavArgCodec
import com.mediahub.model.PlaybackLaunchSnapshot

/** Builds the player route shared by detail "play" and in-player source switching. */
object PlayerRouteBuilder {
    const val PATTERN = "player/{serverId}/{itemId}?title={title}&type={type}&runtime={runtime}&poster={poster}&container={container}&start={start}"

    fun build(
        serverId: String,
        itemId: String,
        snapshot: PlaybackLaunchSnapshot,
        startPositionMs: Long? = null,
    ): String {
        val builder = StringBuilder("player/")
            .append(Uri.encode(serverId))
            .append('/')
            .append(NavArgCodec.encode(itemId))
            .append("?title=").append(Uri.encode(snapshot.title))
            .append("&type=").append(Uri.encode(snapshot.type?.name.orEmpty()))
            .append("&runtime=").append(snapshot.runtimeMs?.toString().orEmpty())
            .append("&poster=").append(Uri.encode(snapshot.posterUrl.orEmpty()))
            .append("&container=").append(Uri.encode(snapshot.container.orEmpty()))
        if (startPositionMs != null) builder.append("&start=").append(startPositionMs)
        return builder.toString()
    }
}
