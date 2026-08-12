package com.mediahub.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.IdGenerator
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.AuthenticationCoordinator
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaProviderRegistry
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddServerUiState(
    val providers: List<ProviderDescriptor> = emptyList(),
    val selectedProviderId: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val localTreeUri: String? = null,
    val localTreeName: String? = null,
    val isTesting: Boolean = false,
    val testResult: ConnectionStatus? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val selectedDescriptor: ProviderDescriptor?
        get() = providers.firstOrNull { it.providerId == selectedProviderId }
}

@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val registry: MediaProviderRegistry,
    private val authenticationCoordinator: AuthenticationCoordinator,
    private val logger: Logger,
) : ViewModel() {
    private val availableDescriptors = registry.descriptors
    private val initialDescriptor = availableDescriptors.firstOrNull { it.isSelectable }

    private val _uiState = MutableStateFlow(
        AddServerUiState(
            providers = availableDescriptors,
            selectedProviderId = initialDescriptor?.providerId.orEmpty(),
            name = initialDescriptor?.displayName.orEmpty(),
        )
    )
    val uiState: StateFlow<AddServerUiState> = _uiState.asStateFlow()

    fun selectProvider(providerId: String) {
        val descriptor = registry.descriptorFor(providerId) ?: return
        if (!descriptor.isSelectable) return
        _uiState.update {
            it.copy(
                selectedProviderId = providerId,
                name = descriptor.displayName,
                baseUrl = "",
                username = "",
                password = "",
                localTreeUri = null,
                localTreeName = null,
                testResult = null,
                error = null,
            )
        }
    }

    fun updateName(name: String) = _uiState.update { it.copy(name = name) }
    fun updateBaseUrl(url: String) = _uiState.update { it.copy(baseUrl = url, testResult = null) }
    fun updateUsername(username: String) = _uiState.update { it.copy(username = username) }
    fun updatePassword(password: String) = _uiState.update { it.copy(password = password) }

    fun updateLocalTree(uri: String, displayName: String) = _uiState.update {
        it.copy(localTreeUri = uri, localTreeName = displayName, testResult = null, error = null)
    }

    fun reportDirectoryGrantError(message: String) = _uiState.update { it.copy(error = message) }

    fun testConnection() {
        val state = _uiState.value
        val draft = runCatching { buildServer(state, id = "connection-probe") }
            .getOrElse {
                _uiState.update { current -> current.copy(testResult = ConnectionStatus(false, message = it.message)) }
                return
            }
        val handle = registry.create(draft)
        if (handle == null) {
            _uiState.update { it.copy(testResult = ConnectionStatus(false, message = "Provider 尚未注册")) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null, error = null) }
            val result = runCatching {
                handle.provider.testConnection(ConnectionTestRequest(credentials = credentialsFor(state)))
            }.getOrElse { ConnectionStatus(false, message = it.message ?: "连接测试失败") }
            _uiState.update { it.copy(isTesting = false, testResult = result) }
        }
    }

    fun save(onSaved: (MediaServer) -> Unit) {
        val state = _uiState.value
        val server = runCatching { buildServer(state, id = IdGenerator.newId("srv")) }
            .getOrElse {
                _uiState.update { current -> current.copy(error = it.message) }
                return
            }
        val handle = registry.create(server)
        if (handle == null) {
            _uiState.update { it.copy(error = "Provider 尚未注册") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            var persisted = false
            try {
                if (handle.descriptor.category == ProviderCategory.LOCAL_STORAGE) {
                    val status = handle.provider.testConnection()
                    if (!status.ok) error(status.message ?: "本地目录不可访问")
                }
                val saved = serverRepository.addServer(server)
                persisted = true
                credentialsFor(state)?.let { credentials ->
                    authenticationCoordinator.authenticateOrDefer(handle, credentials)
                }
                logger.i(
                    LogTag.UI,
                    "已添加媒体源 serverId=${saved.id} providerId=${saved.providerId}",
                )
                _uiState.update { it.copy(isSaving = false) }
                onSaved(saved)
            } catch (e: Exception) {
                if (persisted) serverRepository.deleteServer(server.id)
                runCatching { authenticationCoordinator.clear(server.id) }
                    .onFailure { logger.w(LogTag.AUTH, "回滚凭据失败 serverId=${server.id}", it) }
                logger.e(LogTag.UI, "添加媒体源失败 providerId=${server.providerId}", e)
                _uiState.update { it.copy(isSaving = false, error = "保存失败：${e.message}") }
            }
        }
    }

    private fun buildServer(state: AddServerUiState, id: String): MediaServer {
        val descriptor = requireNotNull(state.selectedDescriptor) { "请选择媒体源类型" }
        require(descriptor.isSelectable) { "${descriptor.displayName} 即将支持" }
        val location = if (descriptor.category == ProviderCategory.LOCAL_STORAGE) {
            requireNotNull(state.localTreeUri) { "请先选择本地媒体目录" }
        } else {
            state.baseUrl.trim().trimEnd('/').also { require(it.isNotBlank()) { "请填写服务器地址" } }
        }
        return MediaServer(
            id = id,
            name = state.name.trim().ifBlank { descriptor.displayName },
            providerId = descriptor.providerId,
            baseUrl = location,
            username = state.username.trim().ifBlank { null },
            createdAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun credentialsFor(state: AddServerUiState): Credentials? {
        val descriptor = state.selectedDescriptor ?: return null
        val username = state.username.trim()
        val password = state.password
        if (username.isBlank() && password.isBlank()) return null
        return when (descriptor.authMethod) {
            AuthMethod.USERNAME_PASSWORD -> Credentials.UsernamePassword(username, password)
            AuthMethod.BASIC -> Credentials.BasicAuth(username, password)
            else -> null
        }
    }
}
