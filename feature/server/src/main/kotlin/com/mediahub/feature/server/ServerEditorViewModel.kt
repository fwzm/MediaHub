package com.mediahub.feature.server

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.common.ServerAddressNormalizer
import com.mediahub.core.security.TokenStore
import com.mediahub.core.network.EndpointTestService
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.model.EndpointQualityResult
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Server Editor 组合状态。 */
data class ServerEditorUiState(
    val isLoading: Boolean = true,
    val server: MediaServer? = null,
    val name: String = "",
    val note: String = "",
    val baseUrl: String = "",
    /** HTTPS 开关（从已存 URL scheme 派生；历史 HTTP 不自动升级）。 */
    val preferHttps: Boolean = true,
    /** 规范化后的完整地址（唯一权威）；非法时为 null，见 [addressError]。 */
    val resolvedUrl: String? = null,
    val addressError: String? = null,
    /** 地址草稿版本：地址/协议变化递增；异步测试结果按发起时版本绑定。 */
    val addressVersion: Long = 0,
    val icon: String? = null,
    val loggedIn: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: ConnectionStatus? = null,
    val isMediaTesting: Boolean = false,
    val mediaQualityResult: EndpointQualityResult? = null,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/**
 * Server Editor：编辑已有媒体源（名称/备注/主线路 URL/图标），测试连接、设为默认、删除。
 * URL 采用「草稿 → 测试（可选）→ 保存 → 一次 updateServer」，边输入边不改变正在使用的地址。
 */
@HiltViewModel
class ServerEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val registry: MediaProviderRegistry,
    private val tokenStore: TokenStore,
    private val serverIconStore: ServerIconStore,
    private val removeServerUseCase: RemoveServerUseCase,
    private val httpClientFactory: HttpClientFactory,
    private val logger: Logger,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedStateHandle["serverId"])

    private val _uiState = MutableStateFlow(ServerEditorUiState())
    val uiState: StateFlow<ServerEditorUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val server = serverRepository.getServer(serverId)
            if (server == null) {
                _uiState.update { it.copy(isLoading = false, error = "找不到媒体源") }
            } else {
                val loggedIn = tokenStore.readTokens(server.id) != null
                // 历史 HTTP 地址不自动升级：开关从已存 URL 的 scheme 派生（Phase 1I）
                val detected = ServerAddressNormalizer.explicitHttpScheme(server.baseUrl)
                val normalized = ServerAddressNormalizer.normalize(server.baseUrl, preferHttps = true)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        server = server,
                        name = server.name,
                        note = server.note.orEmpty(),
                        baseUrl = server.baseUrl,
                        preferHttps = detected?.let { s -> s == "https" } ?: true,
                        resolvedUrl = (normalized as? ServerAddressNormalizer.Result.Ok)?.address?.url,
                        addressError = (normalized as? ServerAddressNormalizer.Result.Invalid)?.error?.userMessage,
                        addressVersion = 1,
                        icon = server.icon,
                        loggedIn = loggedIn,
                    )
                }
            }
        }
    }

    fun updateName(name: String) = _uiState.update { it.copy(name = name) }
    fun updateNote(note: String) = _uiState.update { it.copy(note = note) }

    /** 地址输入：重新规范化 + 草稿版本递增 + 清除旧测试结果（Phase 1I）。 */
    fun updateBaseUrl(raw: String) = _uiState.update { st ->
        val detected = ServerAddressNormalizer.explicitHttpScheme(raw)
        val preferHttps = detected?.let { it == "https" } ?: st.preferHttps
        val normalized = ServerAddressNormalizer.normalize(raw, preferHttps)
        st.copy(
            baseUrl = raw,
            preferHttps = preferHttps,
            resolvedUrl = (normalized as? ServerAddressNormalizer.Result.Ok)?.address?.url,
            addressError = (normalized as? ServerAddressNormalizer.Result.Invalid)?.error?.userMessage,
            addressVersion = st.addressVersion + 1,
            testResult = null,
            mediaQualityResult = null,
        )
    }

    /** 手动切换 HTTPS 开关：只改 scheme（粘贴进来的完整 URL 同样重写文本协议），端口与子路径保留。 */
    fun toggleHttps(enabled: Boolean) = _uiState.update { st ->
        val target = if (enabled) "https" else "http"
        val newText = if (st.baseUrl.isBlank()) st.baseUrl else ServerAddressNormalizer.withScheme(st.baseUrl, target)
        val normalized = ServerAddressNormalizer.normalize(newText, enabled)
        st.copy(
            baseUrl = newText,
            preferHttps = enabled,
            resolvedUrl = (normalized as? ServerAddressNormalizer.Result.Ok)?.address?.url,
            addressError = (normalized as? ServerAddressNormalizer.Result.Invalid)?.error?.userMessage,
            addressVersion = st.addressVersion + 1,
            testResult = null,
            mediaQualityResult = null,
        )
    }

    /** 协议级连接测试（草稿地址）：非法地址请求前拦截；结果按草稿版本绑定。 */
    fun testConnection() {
        val state = _uiState.value
        if (state.resolvedUrl == null) {
            _uiState.update {
                it.copy(testResult = ConnectionStatus(ok = false, message = state.addressError ?: "服务器地址无效"))
            }
            return
        }
        val versionAtRequest = state.addressVersion
        val server = buildEditedServer(state) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, testResult = null) }
            val handle = registry.create(server)
            val result = if (handle != null) {
                runCatching { handle.provider.testConnection() }
                    .getOrElse { e -> ConnectionStatus(ok = false, message = e.message ?: "连接测试失败") }
            } else {
                ConnectionStatus(ok = false, message = "不支持的媒体源类型")
            }
            _uiState.update { st ->
                if (AddressTestResultPolicy.shouldApply(st.addressVersion, versionAtRequest)) {
                    st.copy(isTesting = false, testResult = result)
                } else {
                    st.copy(isTesting = false)
                }
            }
        }
    }

    /**
     * 两层线路质量测试（U4-D）：API latency + Media Range 1MB。
     * 结果经 ServerRepository.updateEndpointQuality() 持久化到 Room。
     */
    fun testMediaQuality() {
        val state = _uiState.value
        val url = state.resolvedUrl
        if (url == null || url.isBlank()) return
        // ADR-039：探针路径来自 Provider 自述（descriptor.probePath）；
        // 未知/未定义类型 = 显式不可用，绝不静默回退其他协议的路径。
        val serverType = state.server?.type
        val probePath = serverType?.let { registry.factoryFor(it)?.descriptor?.probePath }
        if (serverType == null || probePath == null) {
            _uiState.update {
                it.copy(
                    isMediaTesting = false,
                    mediaQualityResult = EndpointQualityResult(
                        endpointId = serverId,
                        error = "该媒体源类型不支持线路质量测试",
                    ),
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isMediaTesting = true, mediaQualityResult = null) }
            val service = EndpointTestService(httpClientFactory)
            val raw = service.test(url, probePath)
            val result = EndpointQualityResult(
                endpointId = serverId,
                apiLatencyMs = raw.apiLatencyMs.takeIf { it > 0 },
                mediaFirstByteMs = raw.mediaFirstByteMs,
                downloadSpeedBytesPerSec = raw.mediaThroughputMbps?.let { (it * 1024 * 1024).toLong() },
                httpStatus = raw.httpCode.takeIf { it > 0 },
                protocol = raw.protocol,
                supportsRange = raw.supportsRange,
                error = raw.error,
            )
            serverRepository.updateEndpointQuality(
                serverId = serverId,
                apiLatencyMs = result.apiLatencyMs,
                mediaFirstByteMs = result.mediaFirstByteMs,
                throughputMbps = raw.mediaThroughputMbps,
                protocol = result.protocol,
                supportsRange = result.supportsRange,
                httpCode = result.httpStatus,
            )
            _uiState.update { it.copy(isMediaTesting = false, mediaQualityResult = result) }
        }
    }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        val server = buildEditedServer(state) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                serverRepository.updateServer(server)
                _uiState.update { it.copy(isSaving = false, server = server) }
                onSaved()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = "保存失败：" + e.message) }
            }
        }
    }

    fun setDefault() {
        viewModelScope.launch {
            try {
                serverRepository.setDefault(serverId)
                _uiState.update {
                    it.copy(
                        message = "已设为默认媒体源",
                        server = it.server?.copy(isDefault = true),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "设置失败：" + e.message) }
            }
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val server = _uiState.value.server ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, error = null) }
            try {
                removeServerUseCase(server)
                onDeleted()
            } catch (e: Exception) {
                logger.e(LogTag.UI, "删除媒体源失败", e)
                _uiState.update { it.copy(isDeleting = false, error = "删除失败：" + e.message) }
            }
        }
    }

    /** 从 Photo Picker 保存自定义图标（复制到应用私有目录）。 */
    fun saveIcon(uri: Uri) {
        viewModelScope.launch {
            try {
                val ref = serverIconStore.saveFromUri(serverId, uri)
                _uiState.update { it.copy(icon = ref) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "设置图标失败：" + e.message) }
            }
        }
    }

    /** 使用内置 Provider 图标（builtin://<type>）；未知类型显式不动，禁止静默回退 Emby（ADR-039）。 */
    fun useBuiltinIcon() {
        val type = _uiState.value.server?.type?.name?.lowercase() ?: return
        _uiState.update { it.copy(icon = "builtin://" + type) }
    }

    fun removeIcon() {
        viewModelScope.launch {
            serverIconStore.remove(serverId)
            _uiState.update { it.copy(icon = null) }
        }
    }

    /** 由草稿构建待保存 MediaServer：保留 id/type/元数据，只改 name/note/icon/主线路 URL。 */
    private fun buildEditedServer(state: ServerEditorUiState): MediaServer? {
        val original = state.server ?: return null
        val url = state.resolvedUrl ?: return null
        val endpoints = if (original.endpoints.isEmpty()) {
            if (url.isBlank()) emptyList() else listOf(
                ServerEndpoint(
                    id = "",
                    serverId = original.id,
                    name = "默认线路",
                    url = url,
                    isPrimary = true,
                    enabled = true,
                    sortOrder = 0,
                )
            )
        } else {
            original.endpoints.mapIndexed { index, ep ->
                if (index == 0) ep.copy(url = url) else ep
            }
        }
        return original.copy(
            name = state.name.trim().ifBlank { original.name },
            note = state.note.trim().ifBlank { null },
            icon = state.icon,
            endpoints = endpoints,
        )
    }
}