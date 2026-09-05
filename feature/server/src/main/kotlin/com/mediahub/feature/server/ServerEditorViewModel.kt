package com.mediahub.feature.server

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.ServerAddressNormalizer
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.EndpointTestService
import com.mediahub.core.security.TokenStore
import com.mediahub.model.EndpointQualityResult
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
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
    /** 规范化后的完整网络地址（唯一权威）；LOCAL 恒为 null；非法输入时为 null，见 [addressError]。 */
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
 *
 * Phase 1I：
 * - 地址输入经 [ServerAddressNormalizer] 集中规范化（HTTPS 开关语义见组件文档）；
 * - **LOCAL 媒体源不参与网络地址规范化**——目录/路径属于设备环境，编辑只改元数据，
 *   endpoints 原样保留，不补 `https://` 前缀；
 * - 线路质量测试（[testMediaQuality]）结果按"草稿版本 + 持久化目标归属"双闸隔离：
 *   草稿已变化的在途结果不显示；被测 URL 不是已保存线路当前地址时不落库
 *   （未保存草稿的结果只展示，不得覆盖已保存线路的质量数据）。
 */
@HiltViewModel
class ServerEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val serverStore: ServerStore,
    private val registry: MediaProviderRegistry,
    private val tokenStore: TokenStore,
    private val serverIconStore: ServerIconStore,
    private val removeHandler: ServerRemoveHandler,
    private val endpointTestService: EndpointTestService,
    private val logger: Logger,
) : ViewModel() {
    private val serverId: String = checkNotNull(savedStateHandle["serverId"])

    private val _uiState = MutableStateFlow(ServerEditorUiState())
    val uiState: StateFlow<ServerEditorUiState> = _uiState.asStateFlow()

    /** 在途线路质量任务；地址/协议变化即取消（返回后的版本检查仍保留）。 */
    private var qualityJob: Job? = null

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val server = serverStore.getServer(serverId)
            if (server == null) {
                _uiState.update { it.copy(isLoading = false, error = "找不到媒体源") }
            } else {
                val loggedIn = tokenStore.readTokens(server.id) != null
                if (server.type == ServerType.LOCAL) {
                    // 本地媒体源：无网络地址语义，不做规范化（Phase 1I review P1——
                    // 元数据编辑不得被网络地址校验拦截），endpoints 原样保留。
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            server = server,
                            name = server.name,
                            note = server.note.orEmpty(),
                            baseUrl = server.baseUrl,
                            resolvedUrl = null,
                            addressError = null,
                            icon = server.icon,
                            loggedIn = loggedIn,
                        )
                    }
                } else {
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
    }

    fun updateName(name: String) = _uiState.update { it.copy(name = name) }
    fun updateNote(note: String) = _uiState.update { it.copy(note = note) }

    /** 地址输入：重新规范化 + 草稿版本递增 + 清除旧测试/质量结果 + 取消在途质量任务。 */
    fun updateBaseUrl(raw: String) {
        qualityJob?.cancel()
        _uiState.update { st ->
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
    }

    /** 手动切换 HTTPS 开关：只改 scheme（粘贴进来的完整 URL 同样重写文本协议），端口与子路径保留。 */
    fun toggleHttps(enabled: Boolean) {
        qualityJob?.cancel()
        _uiState.update { st ->
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
    }

    /** 协议级连接测试（草稿地址）：非法地址请求前拦截；结果按草稿版本绑定。 */
    fun testConnection() {
        val state = _uiState.value
        val server = buildEditedServer(state) ?: run {
            _uiState.update {
                it.copy(testResult = ConnectionStatus(ok = false, message = it.addressError ?: "服务器地址无效"))
            }
            return
        }
        val versionAtRequest = state.addressVersion
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
     *
     * Phase 1I review P2——结果按**双闸**隔离：
     * 1. 显示闸（[AddressTestResultPolicy]）：测试发起后地址/协议变化 → 在途任务已取消，
     *    返回后版本仍不符则丢弃，不显示；
     * 2. 持久化闸：仅当被测 URL == 已保存主线路当前地址时才写库。测试对象只是未保存
     *    草稿时，结果可展示在草稿下，但不得覆盖已保存线路的质量数据。
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
        val versionAtRequest = state.addressVersion
        val savedEndpointUrl = state.server?.endpoints
            ?.firstOrNull { it.isPrimary }?.url
            ?: state.server?.endpoints?.firstOrNull()?.url
        qualityJob?.cancel()
        qualityJob = viewModelScope.launch {
            _uiState.update { it.copy(isMediaTesting = true, mediaQualityResult = null) }
            val raw = endpointTestService.test(url, probePath)
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
            val current = _uiState.value
            val matchesDraft = AddressTestResultPolicy.shouldApply(current.addressVersion, versionAtRequest)
            val targetsSavedEndpoint = savedEndpointUrl == url
            _uiState.update {
                if (matchesDraft) it.copy(isMediaTesting = false, mediaQualityResult = result)
                else it.copy(isMediaTesting = false)
            }
            if (targetsSavedEndpoint) {
                serverStore.updateEndpointQuality(
                    serverId = serverId,
                    apiLatencyMs = result.apiLatencyMs,
                    mediaFirstByteMs = result.mediaFirstByteMs,
                    throughputMbps = raw.mediaThroughputMbps,
                    protocol = result.protocol,
                    supportsRange = result.supportsRange,
                    httpCode = result.httpStatus,
                )
            } else {
                logger.i(
                    LogTag.UI,
                    "线路质量测试对象为未保存草稿地址，结果仅展示不落库 serverId=$serverId",
                )
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        val server = buildEditedServer(state) ?: run {
            _uiState.update { it.copy(error = state.addressError ?: "服务器地址无效") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                serverStore.updateServer(server)
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
                serverStore.setDefault(serverId)
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
                removeHandler.remove(server)
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

    /**
     * 由草稿构建待保存 MediaServer：保留 id/type/元数据，只改 name/note/icon/主线路 URL。
     * LOCAL 媒体源：endpoints 原样保留（目录/路径属于设备环境，不参与网络地址规范化）；
     * 网络媒体源地址非法时返回 null（调用方呈现 [ServerEditorUiState.addressError]）。
     */
    private fun buildEditedServer(state: ServerEditorUiState): MediaServer? {
        val original = state.server ?: return null
        if (original.type == ServerType.LOCAL) {
            return original.copy(
                name = state.name.trim().ifBlank { original.name },
                note = state.note.trim().ifBlank { null },
                icon = state.icon,
            )
        }
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
