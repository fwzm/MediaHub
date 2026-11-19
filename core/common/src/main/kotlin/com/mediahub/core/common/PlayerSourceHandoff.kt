package com.mediahub.core.common

import com.mediahub.model.PlaybackSourceOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local handoff from the detail source resolver to the player.
 *
 * ADR-038 keeps canonical occurrences out of navigation arguments, so the detail page
 * publishes the already-resolved playable lines here and the player reads the entry
 * matching its own serverId/itemId. Entries are keyed by both ids: a stale detail page
 * can never leak its lines into another item's player. This is intentionally memory-only;
 * after process death the player simply starts with a single (current) source.
 */
object PlayerSourceHandoff {
    private const val MAX_OPTIONS = 64

    data class Snapshot(
        val serverId: String,
        val itemId: String,
        val options: List<PlaybackSourceOption>,
        val truncated: Boolean,
    )

    private val entries = ConcurrentHashMap<String, Snapshot>()

    fun publish(
        serverId: String,
        itemId: String,
        options: List<PlaybackSourceOption>,
        truncated: Boolean = false,
    ) {
        val limited = if (options.size > MAX_OPTIONS) options.take(MAX_OPTIONS) else options
        entries[key(serverId, itemId)] = Snapshot(
            serverId = serverId,
            itemId = itemId,
            options = limited,
            truncated = truncated || options.size > MAX_OPTIONS,
        )
    }

    fun snapshotFor(serverId: String, itemId: String): Snapshot? = entries[key(serverId, itemId)]

    fun clearFor(serverId: String, itemId: String) {
        entries.remove(key(serverId, itemId))
    }

    private fun key(serverId: String, itemId: String): String = serverId + "\u0000" + itemId
}
