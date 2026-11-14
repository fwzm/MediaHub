package com.mediahub.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.prefs.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 资源标签文件夹展示顺序（条目 4）。
 *
 * 与 [LibraryViewModel] 分离：分页/筛选状态由后者负责，这里只持久化展示顺序，
 * 避免把 UI 偏好塞进浏览数据流。
 */
@HiltViewModel
class LibraryAppearanceViewModel @Inject constructor(
    private val preferences: UserPreferencesRepository,
) : ViewModel() {

    val libraryOrder: StateFlow<List<String>> = preferences.flow
        .map { it.libraryOrder }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 按当前有序 id 列表重排并持久化；非法/原位移动不写存储。 */
    fun move(from: Int, to: Int, currentIds: List<String>) {
        val next = reorderIds(currentIds, from, to)
        if (next == currentIds) return
        viewModelScope.launch {
            preferences.update { it.copy(libraryOrder = next) }
        }
    }
}
