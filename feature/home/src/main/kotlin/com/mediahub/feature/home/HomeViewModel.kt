package com.mediahub.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ProgressRepository
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.provider.api.AuthenticationCoordinator
import com.mediahub.provider.api.AuthenticationState
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    progressRepository: ProgressRepository,
    private val registry: MediaProviderRegistry,
    private val authenticationCoordinator: AuthenticationCoordinator,
    private val logger: Logger,
) : ViewModel() {

    private val _authStates = MutableStateFlow<Map<String, AuthenticationState>>(emptyMap())
    val authStates: StateFlow<Map<String, AuthenticationState>> = _authStates.asStateFlow()

    private val providerNames = registry.descriptors.associate { it.providerId to it.displayName }
    private val providerCategories = registry.descriptors.associate { it.providerId to it.category }

    fun providerDisplayName(providerId: String): String = providerNames[providerId] ?: providerId

    fun locationLabel(server: MediaServer): String =
        if (providerCategories[server.providerId] == ProviderCategory.LOCAL_STORAGE) {
            if (server.baseUrl.isBlank()) "需要重新授权本地目录" else "已授权本地目录"
        } else {
            server.baseUrl
        }

    fun requiresReauthorization(server: MediaServer): Boolean =
        providerCategories[server.providerId] == ProviderCategory.LOCAL_STORAGE && server.baseUrl.isBlank()

    val servers: StateFlow<List<MediaServer>> = serverRepository.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching: StateFlow<List<PlaybackProgress>> =
        progressRepository.observeContinueWatching(limit = 20)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // 真正接到 App 首屏：每次服务器集合变化，仅恢复尚未检查的可认证会话。
        viewModelScope.launch {
            servers.collectLatest { current ->
                val currentIds = current.mapTo(mutableSetOf()) { it.id }
                _authStates.update { states -> states.filterKeys(currentIds::contains) }
                current.forEach { server ->
                    if (_authStates.value.containsKey(server.id)) return@forEach
                    val handle = registry.create(server) ?: return@forEach
                    if (handle.auth == null) return@forEach
                    val state = authenticationCoordinator.restore(handle)
                    _authStates.update { it + (server.id to state) }
                    if (state is AuthenticationState.Unavailable) {
                        logger.w(LogTag.AUTH, "会话恢复暂时不可用 serverId=${server.id}", state.error)
                    }
                }
            }
        }
    }

    /** 用户主动登出（review #8）：AuthenticationCoordinator.logout（服务端 best-effort + Vault 清理），随后回未登录。 */
    fun logout(serverId: String) {
        viewModelScope.launch {
            val server = servers.first().firstOrNull { it.id == serverId } ?: return@launch
            val handle = registry.create(server)
            if (handle?.auth != null) {
                runCatching { authenticationCoordinator.logout(handle) }
                    .onFailure { logger.w(LogTag.UI, "登出失败 serverId=$serverId", it) }
            }
            _authStates.update { it + (serverId to AuthenticationState.SignedOut) }
        }
    }
}
