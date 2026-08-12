package com.mediahub.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.MediaProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val serverStore: ServerStore,
    progressRepository: ProgressStore,
    private val registry: MediaProviderRegistry,
    private val logger: Logger,
) : ViewModel() {

    val servers: StateFlow<List<MediaServer>> = serverStore.observeServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val continueWatching: StateFlow<List<PlaybackProgress>> =
        progressRepository.observeContinueWatching(limit = 20)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 各服务器登录态（restoreSession 驱动，review：App 启动真正恢复登录）。 */
    private val _authStates = MutableStateFlow<Map<String, AuthSessionState>>(emptyMap())
    val authStates: StateFlow<Map<String, AuthSessionState>> = _authStates.asStateFlow()

    init {
        // 服务器列表变化（含启动加载、添加新服务器）时，对未检查的服务器做会话恢复
        viewModelScope.launch {
            servers.collect { list ->
                val known = _authStates.value.keys
                list.filter { it.id !in known }.forEach { restore(it) }
            }
        }
    }

    private suspend fun restore(server: MediaServer) {
        val handle = registry.create(server)
        // 非认证 Provider（Local/WebDAV 无 auth）不进入 authStates：不显示 未登录/Restoring/Logout
        val auth = handle?.auth
        if (auth == null) {
            _authStates.update { it - server.id }
            return
        }
        _authStates.update { it + (server.id to AuthSessionState.Restoring) }
        val result = runCatching { auth.restoreSession() }
            .getOrElse { e ->
                logger.w(LogTag.UI, "会话恢复失败 serverId=${server.id}", e)
                AuthSessionState.SignedOut
            }
        _authStates.update { it + (server.id to result) }
    }

    /**
     * 强制恢复某服务器的认证状态（评审 FINAL PATCH 3：re-login 成功后 Home 状态立即刷新）。
     * 不管 authStates 是否有该 id 记录，都重新 restoreSession。
     */
    /**
     * 强制恢复某服务器认证状态（评审 FINAL PATCH 4）。
     *
     * - 直接从 DB 读取最新服务器（re-login 可能改了 baseUrl/username/name，且 Navigation result
     *   可能早于 observeServers StateFlow 更新缓存，不能用 servers.first() 的旧缓存）。
     * - 复用 restore(server)：先确认 handle.auth != null 才写 Restoring（非认证 Provider 不闪现 Restoring）。
     */
    fun forceRestore(serverId: String) {
        viewModelScope.launch {
            val server = serverStore.getServer(serverId) ?: return@launch
            restore(server)
        }
    }

    /** 用户主动登出：auth.logout()（本地清理为权威），随后状态回到未登录。 */
    fun logout(serverId: String) {
        viewModelScope.launch {
            val server = servers.first().firstOrNull { it.id == serverId } ?: return@launch
            val handle = registry.create(server)
            handle?.auth?.let { auth ->
                runCatching { auth.logout() }
                    .onFailure { logger.w(LogTag.UI, "登出失败 serverId=$serverId", it) }
            }
            _authStates.update { it + (serverId to AuthSessionState.SignedOut) }
        }
    }

    /**
     * 卡片点击是否进入"重新登录"（review：Existing Server Re-login）。
     * 仅对"需要认证的 Provider"且状态为 已失效/服务器身份变更/未登录 时返回 true；
     * Local 等无认证 Provider 走正常打开。
     */
    fun needsRelogin(server: MediaServer, authState: AuthSessionState?): Boolean {
        val authProvider = runCatching { registry.create(server)?.auth }.getOrNull()
        // 唯一源：AuthNavigationPolicy；生产代码实际调用（测试也测它，评审 #3）
        return AuthNavigationPolicy.needsRelogin(authProvider != null, authState)
    }
}
