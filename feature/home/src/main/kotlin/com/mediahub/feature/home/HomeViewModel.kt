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
import com.mediahub.feature.server.ServerClickDecision
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

/** 卡片点击目标。 */
sealed interface ServerClickTarget {
    data object Open : ServerClickTarget
    data object LocalReauthorize : ServerClickTarget
    data object AuthRelogin : ServerClickTarget
}

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

    /**
     * 卡片点击目标（Patch 2）：区分 LOCAL reauthorization 与认证 Provider 的 existing-server re-login。
     */
    fun clickTarget(server: MediaServer, authState: AuthenticationState?): ServerClickTarget {
        val category = providerCategories[server.providerId]
        val isAuthProvider = runCatching { registry.create(server)?.auth }.getOrNull() != null
        return when (
            ServerClickDecision.decide(
                category = category,
                requiresLocalReauthorize = category == ProviderCategory.LOCAL_STORAGE && server.baseUrl.isBlank(),
                authState = authState,
                isAuthProvider = isAuthProvider,
            )
        ) {
            ServerClickDecision.Target.LocalReauthorize -> ServerClickTarget.LocalReauthorize
            ServerClickDecision.Target.AuthRelogin -> ServerClickTarget.AuthRelogin
            ServerClickDecision.Target.Open -> ServerClickTarget.Open
        }
    }

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

    /**
     * 强制恢复某服务器认证状态（FINAL PATCH 4：re-login 成功后 Home 状态立即刷新）。
     * 不受 init 里 "containsKey(server.id) 跳过" 的去重逻辑影响；现有状态即使为
     * SignedOut / SessionExpired / Unavailable 也强制覆盖。
     */
    fun forceRestore(serverId: String) {
        viewModelScope.launch {
            val server = servers.first().firstOrNull { it.id == serverId } ?: return@launch
            val handle = registry.create(server)
            val auth = handle?.auth
            if (auth == null) {
                _authStates.update { it - serverId }
                return@launch
            }
            // 直接 restore；AuthenticationState 无 Restoring，恢复完成后立即覆盖结果
            val state = authenticationCoordinator.restore(handle)
            _authStates.update { it + (serverId to state) }
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
