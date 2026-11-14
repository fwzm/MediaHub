package com.mediahub.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.HomeSectionStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页组件展示偏好（条目 3）。
 *
 * 与 [HomeViewModel] 分离，避免把纯 UI 偏好混进服务器/进度数据流；
 * 每个组件按 [com.mediahub.model.HomeSectionIds] 独立取值并独立持久化。
 */
@HiltViewModel
class HomeAppearanceViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {

    val sectionStyles: StateFlow<Map<String, HomeSectionStyle>> = preferences.flow
        .map { it.homeSectionStyles }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 循环切换单个组件的样式；只改该组件，其他组件偏好保持不变。 */
    fun cycleStyle(sectionId: String) {
        viewModelScope.launch {
            preferences.update { current ->
                val next = when (current.homeSectionStyles[sectionId] ?: HomeSectionStyle.CARD) {
                    HomeSectionStyle.CARD -> HomeSectionStyle.COMPACT
                    HomeSectionStyle.COMPACT -> HomeSectionStyle.ROW
                    HomeSectionStyle.ROW -> HomeSectionStyle.CARD
                }
                current.copy(homeSectionStyles = current.homeSectionStyles + (sectionId to next))
            }
        }
    }
}

/** 当前组件样式；未配置时返回默认大卡片。 */
fun sectionStyleOf(styles: Map<String, HomeSectionStyle>, sectionId: String): HomeSectionStyle =
    styles[sectionId] ?: HomeSectionStyle.CARD

/** 继续观看卡片宽度档位（条目 3/26）。 */
fun continueCardWidthDp(style: HomeSectionStyle): Int = when (style) {
    HomeSectionStyle.CARD -> 276
    HomeSectionStyle.COMPACT -> 196
    HomeSectionStyle.ROW -> 0
}
