package com.mediahub.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.trakt.TraktRepository
import com.mediahub.model.TraktCalendarEntry
import com.mediahub.model.TraktResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 追更日历的一天。 */
data class TraktCalendarDay(
    val date: String,
    val entries: List<TraktCalendarEntry>,
)

/**
 * 条目 146：追更日历状态。
 *
 * - [configured]=false：凭据缺失，显示明确提示而不是空白/崩溃；
 * - [error]：加载失败的可读原因（限流含等待秒数）。
 */
data class TraktCalendarUiState(
    val loading: Boolean = true,
    val configured: Boolean = true,
    val days: List<TraktCalendarDay> = emptyList(),
    val error: String? = null,
)

/** 按播出日期分组并升序排序（纯函数，便于单测）。 */
fun groupTraktCalendar(entries: List<TraktCalendarEntry>): List<TraktCalendarDay> =
    entries
        .groupBy { it.firstAired }
        .toSortedMap()
        .map { (date, list) ->
            TraktCalendarDay(
                date = date,
                entries = list.sortedWith(compareBy({ it.season }, { it.episode })),
            )
        }

@HiltViewModel
class TraktCalendarViewModel @Inject constructor(
    private val repository: TraktRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TraktCalendarUiState())
    val state: StateFlow<TraktCalendarUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = TraktCalendarUiState(loading = true, configured = repository.isConfigured)
        viewModelScope.launch {
            if (!repository.isConfigured) {
                _state.value = TraktCalendarUiState(
                    loading = false,
                    configured = false,
                    error = "未配置 Trakt 凭据，无法加载追更日历",
                )
                return@launch
            }
            when (val result = repository.getCalendar(LocalDate.now(), CALENDAR_DAYS)) {
                is TraktResult.Success -> _state.value = TraktCalendarUiState(
                    loading = false,
                    days = groupTraktCalendar(result.value),
                )
                is TraktResult.Failure -> _state.value = TraktCalendarUiState(
                    loading = false,
                    error = result.message,
                )
                TraktResult.NotConfigured -> _state.value = TraktCalendarUiState(
                    loading = false,
                    configured = false,
                    error = "未配置 Trakt 凭据，无法加载追更日历",
                )
            }
        }
    }

    private companion object {
        const val CALENDAR_DAYS = 14
    }
}
