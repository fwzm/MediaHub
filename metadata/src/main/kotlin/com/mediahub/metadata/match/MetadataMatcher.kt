package com.mediahub.metadata.match

import com.mediahub.metadata.MetadataResult
import kotlin.math.abs

/** 匹配理由（UI 可用于解释"为什么推荐这条"，也便于测试断言）。 */
enum class MatchReason {
    TITLE_EXACT,
    ORIGINAL_TITLE_EXACT,
    TITLE_CONTAINS,
    TITLE_SIMILAR,
    YEAR_EXACT,
    YEAR_NEAR,
    YEAR_MISMATCH,
    LANGUAGE_MATCH,
    LANGUAGE_MISMATCH,
}

/** 带分数的候选。分数 ≥ [CONFIDENT_SCORE] 才允许自动采用，否则必须人工确认。 */
data class MatchCandidate(
    val result: MetadataResult,
    val score: Int,
    val reasons: List<MatchReason>,
) {
    val confident: Boolean get() = score >= CONFIDENT_SCORE

    companion object {
        const val CONFIDENT_SCORE = 80
    }
}

/**
 * 候选打分器：标题归一化 + 原名 + 年份 + 语言。
 *
 * 规则（可解释、确定性）：
 * - 标题精确 100；原名精确 95；包含 70；其余按 token/双字重合度给 40–80。
 * - 年份完全一致 +15；相差 1 年 +8；其余 -25。
 * - 语言偏好命中 +10；不一致 -5。
 * - 总分 ≤ 0 的候选直接丢弃（"未知源无匹配"不应靠噪声条目填补）。
 */
class MetadataMatcher {

    fun rank(
        query: String,
        candidates: List<MetadataResult>,
        year: Int? = null,
        preferredLanguage: String? = null,
    ): List<MatchCandidate> {
        val parsed = TitleNormalizer.parse(query)
        if (parsed.title.isBlank()) return emptyList()
        val effectiveYear = year ?: parsed.year
        return candidates
            .mapNotNull { score(parsed.title, it, effectiveYear, preferredLanguage) }
            .sortedWith(compareByDescending<MatchCandidate> { it.score }.thenBy { it.result.id })
    }

    private fun score(
        query: String,
        candidate: MetadataResult,
        year: Int?,
        preferredLanguage: String?,
    ): MatchCandidate? {
        val reasons = mutableListOf<MatchReason>()
        val title = TitleNormalizer.normalize(candidate.title)
        val original = candidate.originalTitle?.let(TitleNormalizer::normalize)
        var score = when {
            title.isNotBlank() && title == query -> {
                reasons += MatchReason.TITLE_EXACT
                100
            }
            !original.isNullOrBlank() && original == query -> {
                reasons += MatchReason.ORIGINAL_TITLE_EXACT
                95
            }
            title.isNotBlank() && (title.contains(query) || query.contains(title)) -> {
                reasons += MatchReason.TITLE_CONTAINS
                70
            }
            else -> {
                val similarity = similarity(query, title)
                if (similarity < MIN_SIMILARITY) return null
                reasons += MatchReason.TITLE_SIMILAR
                (40 + similarity * 40).toInt()
            }
        }

        if (year != null && candidate.year != null) {
            when {
                year == candidate.year -> {
                    reasons += MatchReason.YEAR_EXACT
                    score += 15
                }
                abs(year - candidate.year) == 1 -> {
                    reasons += MatchReason.YEAR_NEAR
                    score += 8
                }
                else -> {
                    reasons += MatchReason.YEAR_MISMATCH
                    score -= 25
                }
            }
        }

        if (!preferredLanguage.isNullOrBlank() && !candidate.language.isNullOrBlank()) {
            if (candidate.language.equals(preferredLanguage, ignoreCase = true)) {
                reasons += MatchReason.LANGUAGE_MATCH
                score += 10
            } else {
                reasons += MatchReason.LANGUAGE_MISMATCH
                score -= 5
            }
        }

        if (score <= 0) return null
        return MatchCandidate(result = candidate, score = score, reasons = reasons)
    }

    /** 拉丁标题按词重合（Jaccard）；中日韩无空格标题按双字重合，避免逐字噪声。 */
    private fun similarity(query: String, candidate: String): Double {
        if (query.isBlank() || candidate.isBlank()) return 0.0
        val queryTokens = tokens(query)
        val candidateTokens = tokens(candidate)
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return 0.0
        val intersection = queryTokens.intersect(candidateTokens).size
        val union = queryTokens.union(candidateTokens).size
        return intersection.toDouble() / union
    }

    private fun tokens(text: String): Set<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size > 1) return words.toSet()
        val single = words.singleOrNull().orEmpty()
        if (single.length < 2) return setOf(single)
        return single.windowed(2).toSet()
    }

    private companion object {
        const val MIN_SIMILARITY = 0.34
    }
}
