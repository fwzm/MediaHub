package com.mediahub.model

/**
 * 徽章包（条目 118）：一组内置徽标的命名单元。
 *
 * 仓库原先没有资源包模型，这里先立最小可用版本：内置包不可删除，
 * 用户只能重命名；重命名映射独立持久化，不改内置包本身。
 */
data class BadgePack(
    val id: String,
    val defaultName: String,
    val badgeIds: List<String> = emptyList(),
)

object BuiltinBadgePacks {
    val EMBY = BadgePack(id = "emby", defaultName = "Emby 徽章")
    val JELLYFIN = BadgePack(id = "jellyfin", defaultName = "Jellyfin 徽章")
    val LOCAL = BadgePack(id = "local", defaultName = "本地徽章")

    val all: List<BadgePack> = listOf(EMBY, JELLYFIN, LOCAL)
}

/** 重命名映射编码：`id=名称|id=名称`，空名称视为未重命名。 */
fun encodeBadgePackNames(names: Map<String, String>): String =
    names.entries
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .sortedBy { it.key }
        .joinToString("|") { "${it.key}=${it.value}" }

fun decodeBadgePackNames(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('|').mapNotNull { part ->
        val index = part.lastIndexOf('=')
        if (index <= 0) return@mapNotNull null
        val id = part.substring(0, index).trim()
        val name = part.substring(index + 1).trim()
        if (id.isEmpty() || name.isEmpty()) null else id to name
    }.toMap()
}

/** 展示名：优先用户重命名，否则回退内置默认名。 */
fun resolveBadgePackName(pack: BadgePack, renames: Map<String, String>): String =
    renames[pack.id]?.takeIf { it.isNotBlank() } ?: pack.defaultName
