package com.mediahub.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.ServerProbeResult
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddServerUiState(
    val selectedType: ServerType = ServerType.EMBY,
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isTesting: Boolean = false,
    val testResult: ConnectionStatus? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AddServerViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val apiClient: ApiClient,
    private val logger: Logger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddServerUiState())
    val uiState: StateFlow<AddServerUiState> = _uiState.asStateFlow()

    fun selectType(type: ServerType) {
        _uiState.update {
            val name = it.name.ifBlank { type.label }
            it.copy(selectedType = type, name = name, testResult = null, error = null)
        }
    }

    fun updateName(name: String) = _uiState.update { it.copy(name = name) }
    fun updateBaseUrl(url: String) = _uiState.update { it.copy(baseUrl = url, testResult = null) }
    fun updateUsername(username: String) = _uiState.update { it.copy(username = username) }
    fun updatePassword(password: String) = _uiState.update { it.copy(password = password) }

    /** 连通性探测（普通 HTTP GET，不涉及鉴权）。 */
    fun testConnection() {
        val type = _uiState.value.selectedType
        if (type == ServerType.LOCAL) {
            _uiState.update {
                it.copy(testResult = ConnectionStatus(ok = true, message = "本地存储无需连接测试"))
            }
            return
        }
        val baseUrl = _uiState.value.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            _uiState.update { it.copy(testResult = ConnectionStatus(ok = false, message = "请先填写服务器地址")) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null, error = null) }
            when (val result = apiClient.probe(baseUrl)) {
                is ServerProbeResult.Success -> _uiState.update {
                    it.copy(
                        isTesting = false,
                        testResult = ConnectionStatus(
                            ok = result.httpCode < 500,
                            latencyMs = result.latencyMs,
                            message = "HTTP ${result.httpCode} · ${result.latencyMs}ms",
                        ),
                    )
                }

                is ServerProbeResult.Failure -> _uiState.update {
                    it.copy(
                        isTesting = false,
                        testResult = ConnectionStatus(ok = false, message = result.userMessage),
                    )
                }
            }
        }
    }

    fun save(onSaved: (MediaServer) -> Unit) {
        val state = _uiState.value
        val type = state.selectedType
        val name = state.name.trim().ifBlank { type.label }
        val baseUrl = state.baseUrl.trim().trimEnd('/')

        if (type != ServerType.LOCAL && baseUrl.isBlank()) {
            _uiState.update { it.copy(error = "请填写服务器地址") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val server = serverRepository.addServer(
                    MediaServer(
                        id = "",
                        name = name,
                        type = type,
                        baseUrl = baseUrl,
                        username = state.username.trim().ifBlank { null },
                        createdAtEpochMs = System.currentTimeMillis(),
                    )
                )
                // 密码仅用于后续 Provider 认证（Phase 1），此处不落库、不记日志。
                logger.i(LogTag.UI, "已添加媒体源 serverId=${server.id} type=${type.name}")
                onSaved(server)
            } catch (e: Exception) {
                logger.e(LogTag.UI, "添加媒体源失败", e)
                _uiState.update { it.copy(isSaving = false, error = "保存失败：${e.message}") }
            }
        }
    }
}
