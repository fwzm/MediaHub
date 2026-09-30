package com.mediahub.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.ServerAddressIdentity
import com.mediahub.core.database.repository.ProgressStore
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthSessionState
import com.mediahub.provider.api.MediaProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** 身份与显示字段分离：同 ID 的地址/账号/类型变化必须重新读已失效的登录态。 */
    private data class AuthIdentity(val type: ServerType, val address: String, val username: String?)
    private data class AuthAttempt(val identity: AuthIdentity, val generation: Long)
    private val attempts = mutableMapOf<String, AuthAttempt>()
    private val restoreJobs = mutableMapOf<String, Job>()
    // 保留已删除 ID 的代号，防止挂起的 DB 读取跨越“新增再删除”后把旧身份复活。
    private val readRevisions = mutableMapOf<String, Long>()
    private var generation = 0L

    private fun identityOf(server: MediaServer) = AuthIdentity(
        server.type, ServerAddressIdentity.normalize(server.baseUrl), server.username,
    )

    init {
        // 不在 collector 内等待网络：旧身份的慢请求不能阻塞恢复后的新身份。
        viewModelScope.launch {
            servers.collect { list ->
                val present = list.map { it.id }.toSet()
                attempts.keys.filter { it !in present }.forEach(::forgetServer)
                list.filter { attempts[it.id]?.identity != identityOf(it) }.forEach(::startRestore)
            }
        }
    }

    private fun forgetServer(serverId: String) {
        readRevisions[serverId] = ++generation
        attempts.remove(serverId)
        restoreJobs.remove(serverId)?.cancel()
        _authStates.update { it - serverId }
    }

    private fun beginAttempt(server: MediaServer): AuthAttempt {
        val attempt = AuthAttempt(identityOf(server), ++generation)
        readRevisions[server.id] = attempt.generation
        // 先作废旧代号再取消；即使 Provider 返回迟到结果也不能恢复旧展示。
        attempts[server.id] = attempt
        restoreJobs.remove(server.id)?.cancel()
        _authStates.update { it - server.id }
        return attempt
    }

    private fun publish(serverId: String, attempt: AuthAttempt, state: AuthSessionState?) {
        if (attempts[serverId] != attempt) return
        _authStates.update { current -> if (state == null) current - serverId else current + (serverId to state) }
    }

    private fun startRestore(server: MediaServer) {
        val attempt = beginAttempt(server)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val auth = registry.create(server)?.auth
                // 非认证 Provider 没有认证卡片状态；仍记录身份以免重命名触发重复检查。
                if (auth == null) return@launch
                publish(server.id, attempt, AuthSessionState.Restoring)
                publish(server.id, attempt, auth.restoreSession())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (attempts[server.id] == attempt) {
                    logger.w(LogTag.UI, "会话恢复失败 serverId=${server.id}", error)
                    publish(server.id, attempt, AuthSessionState.SignedOut)
                }
            } finally {
                if (attempts[server.id] == attempt) restoreJobs.remove(server.id)
            }
        }
        restoreJobs[server.id] = job
        job.start()
    }

    /**
     * 强制恢复某服务器认证状态（评审 FINAL PATCH 4）。
     *
     * - 直接从 DB 读取最新服务器（re-login 可能改了 baseUrl/username/name，且 Navigation result
     *   可能早于 observeServers StateFlow 更新缓存，不能用 servers.first() 的旧缓存）。
     * - 新代号替代旧恢复操作；非认证 Provider 不闪现 Restoring。
     */
    fun forceRestore(serverId: String) {
        viewModelScope.launch {
            val expectedRevision = readRevisions[serverId]
            val server = serverStore.getServer(serverId)
            // getServer 可在 Room 挂起后返回旧快照；身份已变化时不得为旧地址创建新 handle。
            if (readRevisions[serverId] != expectedRevision) return@launch
            if (server == null) forgetServer(serverId) else startRestore(server)
        }
    }

    /** 用户主动登出：auth.logout()（本地清理为权威），随后状态回到未登录。 */
    fun logout(serverId: String) {
        viewModelScope.launch {
            val expectedRevision = readRevisions[serverId]
            val server = serverStore.getServer(serverId) ?: return@launch
            if (readRevisions[serverId] != expectedRevision) return@launch
            val attempt = beginAttempt(server)
            try {
                val auth = registry.create(server)?.auth ?: return@launch
                auth.logout()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.w(LogTag.UI, "登出失败 serverId=$serverId", error)
            }
            publish(serverId, attempt, AuthSessionState.SignedOut)
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
