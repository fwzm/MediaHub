package com.mediahub.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ProgressRepository
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    serverRepository: ServerRepository,
    progressRepository: ProgressRepository,
    registry: MediaProviderRegistry,
) : ViewModel() {

    private val providerNames = registry.descriptors.associate { it.providerId to it.displayName }
    private val providerCategories = registry.descriptors.associate { it.providerId to it.category }

    fun providerDisplayName(providerId: String): String = providerNames[providerId] ?: providerId

    fun locationLabel(server: MediaServer): String =
        if (providerCategories[server.providerId] == ProviderCategory.LOCAL_STORAGE) {
            "已授权本地目录"
        } else {
            server.baseUrl
        }

    val servers: StateFlow<List<MediaServer>> = serverRepository.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching: StateFlow<List<PlaybackProgress>> =
        progressRepository.observeContinueWatching(limit = 20)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
