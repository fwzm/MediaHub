package com.mediahub.feature.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.IdGenerator
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddServerUiState(
    val selectedDescriptorId: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isReauthorizing: Boolean = false,
    val isLoadingExisting: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: ConnectionStatus? = null,
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AddServerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val registry: MediaProviderRegistry,
    private val logger: Logger,
) : ViewModel() {

    /** 可用数据源类型：从 Registry 动态读取（ADR-015），新增 Provider 无需改 UI。 */
    val availableProviders: List<ProviderDescriptor> = registry.descriptors()

    /** 已有服务器重新登录时的原 serverId（review：Existing Server Re-login，复用 same id）。 */
    private val reauthorizeServerId: String = savedStateHandle["reauthorizeId"] ?: ""

    /** 保存前先生成稳定 id（测试连接与保存使用同一实例）。 */
    private val serverId = IdGenerator.newId("srv")

    private var existingServer: MediaServer? = null

    private val _uiState = MutableStateFlow(
        AddServerUiState(
            selectedDescriptorId = availableProviders.firstOrNull()?.id.orEmpty(),
            isReauthorizing = reauthorizeServerId.isNotBlank(),
            isLoadingExisting = reauthorizeServerId.isNotBlank(),
        )
    )
    val uiState: StateFlow<AddServerUiState> = _uiState.asStateFlow()

    init {
        if (reauthorizeServerId.isNotBlank()) {
            viewModelScope.launch {
                val server = serverRepository.getServer(reauthorizeServerId)
                if (server == null) {
                    _uiState.update { it.copy(isLoadingExisting = false, error = "找不到需要重新登录的媒体源") }
                } else {
                    // 复用 SAME id；预填 name/baseUrl/username，密码留空。
                    // descriptor 按 serverType 匹配（禁止 "EMBY" != "emby" 之类把 enum.name 当 ProviderDescriptor.id）
                    val descriptor = ExistingServerEditPolicy.descriptorFor(server, availableProviders)
                    if (descriptor == null) {
                        _uiState.update {
                            it.copy(
                                isLoadingExisting = false,
                                error = "找不到媒体源类型对应的 Provider（type=${server.type}）",
                            )
                        }
                    } else {
                        existingServer = server
                        _uiState.update {
                            it.copy(
                                selectedDescriptorId = descriptor.id,
                                name = server.name,
                                baseUrl = server.baseUrl,
                                username = server.username.orEmpty(),
                                isLoadingExisting = false,
                            )
                        }
                    }
                }
            }
        }
    }

    fun isLoadingExisting(): Boolean = _uiState.value.isLoadingExisting
    fun isReauthorizing(): Boolean = _uiState.value.isReauthorizing

    private fun descriptorOf(id: String): ProviderDescriptor? =
        availableProviders.firstOrNull { it.id == id }

    private fun selectedDescriptor(): ProviderDescriptor? =
        descriptorOf(_uiState.value.selectedDescriptorId)

    fun selectProvider(descriptor: ProviderDescriptor) {
        // existing 模式锁定原 Provider：不允许 UI 切到 Jellyfin/WebDAV 等别的类型
        if (existingServer != null) return
        _uiState.update {
            it.copy(
                selectedDescriptorId = descriptor.id,
                name = it.name.ifBlank { descriptor.displayName },
                testResult = null,
                loginError = null,
                error = null,
            )
        }
    }

    fun updateName(name: String) = _uiState.update { it.copy(name = name) }
    fun updateBaseUrl(url: String) = _uiState.update { it.copy(baseUrl = url, testResult = null) }
    fun updateUsername(username: String) = _uiState.update { it.copy(username = username) }
    fun updatePassword(password: String) = _uiState.update { it.copy(password = password) }

    /** 协议级连接测试：交给具体 Provider 完成（ADR-019）。 */
    fun testConnection() {
        val descriptor = selectedDescriptor() ?: return
        if (descriptor.serverType != ServerType.LOCAL && _uiState.value.baseUrl.isBlank()) {
            _uiState.update {
                it.copy(testResult = ConnectionStatus(ok = false, message = "请先填写服务器地址"))
            }
            return
        }
        val server = buildServer()
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null, error = null) }
            val handle = registry.create(server)
            val result = if (handle != null) {
                handle.provider.testConnection()
            } else {
                ConnectionStatus(ok = false, message = "不支持的媒体源类型")
            }
            _uiState.update { it.copy(isTesting = false, testResult = result) }
        }
    }

    /**
     * 登录并添加（Phase 1A，ADR-026）：
     * 构建临时 MediaServer → Emby authenticate → 成功 → 保存服务器 + 会话/Token → 已登录。
     * 事务语义：认证成功但服务器保存失败 → 清理刚保存的会话（不留孤儿凭据）。
     * 密码仅存在于登录请求内存中，绝不落库、绝不进日志。
     */
    fun loginAndSave(onSaved: (MediaServer) -> Unit) {
        val descriptor = selectedDescriptor() ?: return
        val state = _uiState.value
        if (descriptor.serverType != ServerType.LOCAL && state.baseUrl.isBlank()) {
            _uiState.update { it.copy(loginError = "请填写服务器地址") }
            return
        }
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(loginError = "请输入用户名和密码") }
            return
        }
        val server = buildServer()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingIn = true, loginError = null) }
            try {
                val handle = registry.create(server)
                    ?: throw ProviderException.NotYetImplemented(server.id, "该媒体源类型")
                val auth = handle.auth
                    ?: throw ProviderException.NotYetImplemented(server.id, "该数据源暂不支持登录")
                when (val result = auth.authenticate(
                    Credentials.UsernamePassword(state.username, state.password)
                )) {
                    is AuthResult.Success -> {
                        try {
                            // review：re-login 更新原服务器（不 addServer，不产生重复卡片）
                            if (existingServer != null) serverRepository.updateServer(server)
                            else serverRepository.addServer(server)
                            _uiState.update { it.copy(isLoggingIn = false) }
                            onSaved(server)
                        } catch (e: Exception) {
                            // 事务回滚：认证成功但保存失败 → 清理会话（best-effort）
                            runCatching { auth.logout() }
                            _uiState.update {
                                it.copy(isLoggingIn = false, loginError = "保存失败：${e.message}")
                            }
                        }
                    }

                    is AuthResult.Failure -> _uiState.update {
                        it.copy(isLoggingIn = false, loginError = loginErrorText(result.error))
                    }
                }
            } catch (e: Exception) {
                logger.w(LogTag.UI, "登录失败 serverId=${server.id}", e)
                _uiState.update { it.copy(isLoggingIn = false, loginError = "登录失败：${e.message}") }
            }
        }
    }

    private fun loginErrorText(e: ProviderException): String = when (e) {
        is ProviderException.AuthFailed -> "用户名或密码错误"
        is ProviderException.AuthExpired -> "登录已失效，请重新登录"
        is ProviderException.Network -> "网络错误，请检查服务器地址"
        is ProviderException.Http -> if (e.statusCode in 500..599) {
            "服务器错误（HTTP ${e.statusCode}）"
        } else {
            "HTTP ${e.statusCode}"
        }
        is ProviderException.Parse -> "服务器响应异常"
        else -> e.message ?: "登录失败"
    }

    fun save(onSaved: (MediaServer) -> Unit) {
        val descriptor = selectedDescriptor() ?: return
        val server = buildServer()
        if (descriptor.serverType != ServerType.LOCAL && server.baseUrl.isBlank()) {
            _uiState.update { it.copy(error = "请填写服务器地址") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                if (existingServer != null) serverRepository.updateServer(server)
                else serverRepository.addServer(server)
                // 密码仅用于后续 Provider 认证（Phase 1，经 CredentialVault 加密存储），
                // 此处不落库、不记日志（见 ADR-016）。
                logger.i(LogTag.UI, "已添加媒体源 serverId=${server.id} type=${server.type.name}")
                onSaved(server)
            } catch (e: Exception) {
                logger.e(LogTag.UI, "添加媒体源失败", e)
                _uiState.update { it.copy(isSaving = false, error = "保存失败：${e.message}") }
            }
        }
    }

    private fun buildServer(): MediaServer {
        val descriptor = selectedDescriptor()
        val state = _uiState.value
        val url = state.baseUrl.trim().trimEnd('/')
        // Phase 1B-2.5：地址从单一 baseUrl 迁为线路列表；单线路时生成一条主线路。
        val endpoints = if (descriptor?.serverType == ServerType.LOCAL || url.isBlank()) {
            emptyList()
        } else {
            listOf(
                ServerEndpoint(
                    id = "",
                    serverId = "",
                    name = "默认线路",
                    url = url,
                    isPrimary = true,
                    enabled = true,
                    sortOrder = 0,
                )
            )
        }
        val candidate = MediaServer(
            id = serverId, // 实际 id 由 buildDraft 决定（existing 复用原 id）
            name = state.name.trim().ifBlank { descriptor?.displayName.orEmpty() },
            type = descriptor?.serverType ?: ServerType.EMBY,
            username = state.username.trim().ifBlank { null },
            endpoints = endpoints,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        // existing 模式：复用 SAME id + 完整保留 isDefault/sortOrder/createdAtEpochMs/lastConnectedAtEpochMs/lastError
        return ExistingServerEditPolicy.buildDraft(existingServer, candidate).server
    }
}
