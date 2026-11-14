package com.mediahub.metadata.match

import com.mediahub.metadata.MetadataException
import com.mediahub.metadata.MetadataProvider
import com.mediahub.metadata.MetadataResult
import com.mediahub.metadata.MetadataSource

/** 单个数据源失败记录：用于区分"源不可用"与"确实没有匹配"。 */
data class MatchFailure(
    val source: MetadataSource,
    val statusCode: Int?,
    val message: String,
)

data class MatchSearchOutcome(
    val query: ParsedTitle,
    val candidates: List<MatchCandidate>,
    val failures: List<MatchFailure>,
    /** 前两名分差过小时为 true：即便最高分达阈值也必须人工确认。 */
    val ambiguous: Boolean = false,
)

/**
 * 手动匹配服务（条目 51 / 67）：输入名称 → 跨源搜索 → 归一化打分 → 人工选择。
 *
 * 单个源失败不阻断其它源；空查询不发起任何网络请求。
 * 自动匹配只允许采用 [MatchCandidate.confident] 的结果，其余必须人工确认。
 */
class ManualMatchService(
    private val providers: List<MetadataProvider>,
    private val matcher: MetadataMatcher = MetadataMatcher(),
) {

    suspend fun search(
        query: String,
        year: Int? = null,
        preferredLanguage: String? = null,
    ): MatchSearchOutcome {
        val parsed = TitleNormalizer.parse(query)
        if (parsed.title.isBlank()) {
            return MatchSearchOutcome(parsed, emptyList(), emptyList())
        }
        val effectiveYear = year ?: parsed.year
        val results = mutableListOf<MetadataResult>()
        val failures = mutableListOf<MatchFailure>()
        for (provider in providers) {
            try {
                results += provider.search(parsed.title, effectiveYear)
            } catch (e: MetadataException) {
                failures += MatchFailure(provider.source, e.statusCode, e.message.orEmpty())
            }
        }
        val ranked = matcher.rank(parsed.title, results, effectiveYear, preferredLanguage)
        val ambiguous = ranked.size > 1 && (ranked[0].score - ranked[1].score) < AMBIGUITY_MARGIN
        return MatchSearchOutcome(
            query = parsed,
            candidates = ranked,
            failures = failures,
            ambiguous = ambiguous,
        )
    }

    private companion object {
        const val AMBIGUITY_MARGIN = 10
    }
}
