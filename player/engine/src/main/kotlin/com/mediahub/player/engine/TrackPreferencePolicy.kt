package com.mediahub.player.engine

import android.os.LocaleList
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.Locale

/**
 * 多轨同组默认选轨策略（ADR-048）。
 *
 * 只负责**默认**选择：用户在播放器 Bottom Sheet 里显式选轨后，仍由
 * [TrackSelectionPlanner] 的 override 语义接管，本对象不再参与。
 *
 * 策略阶梯（从强到弱，最终由 Media3 `DefaultTrackSelector` 的真实比较顺序保证）：
 * 1. **语言偏好**：显式用户偏好（当前无 UI/设置项，预留）→ 系统 [LocaleList]，按顺序；
 * 2. **容器 DEFAULT 标记**（`C.SELECTION_FLAG_DEFAULT`）；
 * 3. **设备支持态**：renderer 不支持的编码永不默认选中（硬约束，不是偏好）；
 * 4. **码率 / 声道 / 采样率**：同语言同能力下取质量更高的一条；
 * 5. **首轨兜底**：所有比较项相等时取组内 / 组间第一条，结果确定。
 *
 * 为什么把语言偏好显式写进 selector 参数，而不是交给 Media3 的 locale 次选：
 * 不设置时 Media3 把 `localeLanguageMatchIndex` 排在容器 DEFAULT 标记**之后**，
 * 于是"文件里标了 DEFAULT 的外语轨"会压过用户系统语言（例如系统中文却放英文轨）。
 * 显式传 `preferredAudioLanguages` 把语言放到第一优先级，这是本策略唯一的参数变更。
 *
 * 字幕默认选择由用户的首选字幕语言驱动（清单 82）：只有设置了首选语言时才写入
 * `preferredTextLanguages`，匹配不到时 Media3 不会自动开启字幕；未设置（null）时
 * 完全不改字幕参数，保持既有行为（不自动开启，除非容器 default/forced 标记）。
 */
object TrackPreferencePolicy {

    /**
     * 系统默认语言标签（BCP-47，按用户 LocaleList 顺序）。
     *
     * 只读系统配置，不读取应用内设置；用户级轨道语言偏好尚无产品定义（PENDING）。
     */
    fun systemLanguageTags(): List<String> {
        val locales = LocaleList.getDefault()
        return (0 until locales.size()).map { locales.get(it).toLanguageTag() }
    }

    /**
     * 把系统语言标签收敛成 Media3 偏好语言的有序列表。
     *
     * 规则（确定性、可测试）：取标签的语言子标签（`zh-Hans-CN` → `zh`）并小写、按原顺序去重。
     * 只保留语言级粗匹配，与 Media3 自身 `Util.getSystemLanguageCodes()` 的粒度一致；
     * 繁简 / 地区区分属于产品决策（PENDING），v1 不做。
     */
    fun audioLanguages(systemLanguageTags: List<String>): List<String> =
        systemLanguageTags
            .asSequence()
            .map { it.substringBefore('-').trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /**
     * 把用户首选字幕语言收敛成 Media3 文本偏好语言列表。
     *
     * null / 空白 = 未设置偏好，返回空列表（调用方不得据此自动开启字幕）。
     * 与音频一致，只保留语言级粗匹配（`zh-Hans-CN` → `zh`）。
     */
    fun textLanguages(preferredSubtitleLanguage: String?): List<String> =
        audioLanguages(listOfNotNull(preferredSubtitleLanguage))

    /**
     * 把默认语言偏好写入 selector。
     *
     * - 音频列表为空表示"没有偏好"，不改音频参数，保持 Media3 既有默认行为；
     * - 字幕列表为空表示"未设置首选字幕语言"，会把文本偏好清空，回到不自动开启的默认行为。
     */
    fun prepare(
        selector: DefaultTrackSelector,
        preferredAudioLanguages: List<String>,
        preferredTextLanguages: List<String> = emptyList(),
    ): DefaultTrackSelector {
        if (preferredAudioLanguages.isEmpty() && preferredTextLanguages.isEmpty()) return selector
        val builder = selector.buildUponParameters()
        if (preferredAudioLanguages.isNotEmpty()) {
            builder.setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray())
        }
        builder.setPreferredTextLanguages(*preferredTextLanguages.toTypedArray())
        selector.setParameters(builder)
        return selector
    }
}
