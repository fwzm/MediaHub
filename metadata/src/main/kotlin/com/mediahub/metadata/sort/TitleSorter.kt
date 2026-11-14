package com.mediahub.metadata.sort

import com.mediahub.metadata.MetadataResult
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * 列表页名称排序（条目 91）。
 *
 * - 中文按拼音（`Collator` zh），英文忽略大小写与全半角；
 * - 忽略英文冠词 The / A / An；
 * - 内嵌数字按数值比较（Spider-Man 2 < Spider-Man 10）；
 * - 空标题排最后，同键按 id 稳定排序；
 * - 不剥离年份，避免片名 `1917` 被清空。
 */
class TitleSorter(
    locale: Locale = Locale.SIMPLIFIED_CHINESE,
) {

    private val collator: Collator = Collator.getInstance(locale).apply {
        strength = Collator.SECONDARY
    }

    fun sort(results: List<MetadataResult>): List<MetadataResult> =
        results.sortedWith { left, right ->
            val leftTitle = left.displayTitle()
            val rightTitle = right.displayTitle()
            when {
                leftTitle.isBlank() && rightTitle.isBlank() -> left.id.compareTo(right.id)
                leftTitle.isBlank() -> 1
                rightTitle.isBlank() -> -1
                else -> {
                    val compared = collator.compare(sortKey(leftTitle), sortKey(rightTitle))
                    if (compared != 0) compared else left.id.compareTo(right.id)
                }
            }
        }

    fun sortKey(title: String): String {
        val folded = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase().trim()
        val withoutArticle = ARTICLE.replaceFirst(folded, "")
        val spaced = PUNCTUATION.replace(withoutArticle, " ").trim().replace(WHITESPACE, " ")
        return NUMBER.replace(spaced) { match -> match.value.padStart(8, '0') }
    }

    private fun MetadataResult.displayTitle(): String =
        title.takeIf { it.isNotBlank() } ?: originalTitle.orEmpty()

    private companion object {
        val ARTICLE = Regex("^(the|a|an)\\s+")
        val PUNCTUATION = Regex("[^\\p{L}\\p{N}]+")
        val WHITESPACE = Regex("\\s+")
        val NUMBER = Regex("\\d+")
    }
}
