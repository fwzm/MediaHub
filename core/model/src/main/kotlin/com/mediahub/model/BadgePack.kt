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
    /** 用户自建包可删除；内置包恒为 false。 */
    val custom: Boolean = false,
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

const val CUSTOM_BADGE_PACK_PREFIX = "custom-"

/** 内置包 + 用户自建包（收尾项 3）。 */
fun allBadgePacks(customPacks: List<BadgePack>): List<BadgePack> = BuiltinBadgePacks.all + customPacks

/** 下一个自建包 id：按已有数字后缀取最大值 +1，删除后也不会立刻复用旧 id。 */
fun nextCustomBadgePackId(existingIds: List<String>): String {
    val maxSuffix = existingIds
        .filter { it.startsWith(CUSTOM_BADGE_PACK_PREFIX) }
        .mapNotNull { it.removePrefix(CUSTOM_BADGE_PACK_PREFIX).toIntOrNull() }
        .maxOrNull() ?: 0
    return "$CUSTOM_BADGE_PACK_PREFIX${maxSuffix + 1}"
}

/** 自建包编码：`id\t名称` 逐行。 */
fun encodeCustomBadgePacks(packs: List<BadgePack>): String =
    packs.filter { it.id.isNotBlank() && it.defaultName.isNotBlank() }
        .joinToString("\n") { "${sanitizeBadgePackField(it.id)}\t${sanitizeBadgePackField(it.defaultName)}" }

fun decodeCustomBadgePacks(raw: String?): List<BadgePack> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 2) return@mapNotNull null
        val id = parts[0].trim()
        val name = parts[1].trim()
        if (id.isEmpty() || name.isEmpty()) {
            null
        } else {
            BadgePack(id = id, defaultName = name, custom = true)
        }
    }.toList()
}

/** 删除：只允许删除自建包，内置包 id 传入时原样返回。 */
fun deleteBadgePack(packs: List<BadgePack>, id: String): List<BadgePack> =
    packs.filterNot { it.id == id && it.custom }

private fun sanitizeBadgePackField(value: String): String =
    value.replace('\n', ' ').replace('\t', ' ')
