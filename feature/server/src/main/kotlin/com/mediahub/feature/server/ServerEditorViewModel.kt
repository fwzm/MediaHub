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
import kotlinx.coroutines.CancellationException
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
    /** 质量测试请求身份（发起/草稿变化递增）；与 VM 内计数器比对做返回后归属校验。 */
    val qualityRequestId: Long = 0,
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

    /**
     * 质量测试请求身份：发起与草稿变化均递增。与 [ServerEditorUiState.qualityRequestId]
     * 对比完成"返回后归属校验"与"取消收尾所有权校验"（review P2 round 2）。
     */
    private var qualityRequestId: Long = 0

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
        qualityRequestId += 1 // 使在途请求的身份失效（其收尾不得关闭新状态）
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
                qualityRequestId = qualityRequestId,
                isMediaTesting = false, // 在途任务已被取消：loading 显式复位
                testResult = null,
                mediaQualityResult = null,
            )
        }
    }

    /** 手动切换 HTTPS 开关：只改 scheme（粘贴进来的完整 URL 同样重写文本协议），端口与子路径保留。 */
    fun toggleHttps(enabled: Boolean) {
        qualityJob?.cancel()
        qualityRequestId += 1
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
                qualityRequestId = qualityRequestId,
                isMediaTesting = false, // 在途任务已被取消：loading 显式复位
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
     * Phase 1I review P2（round 2）——**请求身份** + 双闸：
     * 1. 请求身份（[qualityRequestId]）：每次发起递增；地址/协议变化递增并取消在途
     *    任务。返回后身份不符（被更新的请求取代）→ 显示与写库同时禁止。
     * 2. 草稿版本（[AddressTestResultPolicy]）：发起后地址/协议变化 → 显示与写库同时禁止。
     * 3. 持久化归属：仅当被测 URL == 已保存主线路当前地址时才写库；未保存草稿 B 的
     *    结果只展示在草稿下，不得覆盖已保存线路的质量数据。写库携带测试时的
     *    `endpointId + expectedUrl`，由 Repository 在事务内做条件更新（防检查-写入竞态）。
     *
     * 收尾：try/finally + 所有权校验复位 [ServerEditorUiState.isMediaTesting]——
     * 取消与异常路径同样复位；旧任务的收尾不得关闭新任务的 loading。
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
        val targetEndpoint = state.server?.endpoints
            ?.firstOrNull { it.isPrimary }
            ?: state.server?.endpoints?.firstOrNull()
        val endpointIdAtRequest = targetEndpoint?.id
        val expectedUrlAtRequest = targetEndpoint?.url
        qualityRequestId += 1
        val requestId = qualityRequestId
        // 同步身份到状态：同一主调度器，launch 尚未启动，无竞态
        _uiState.update { it.copy(qualityRequestId = requestId) }
        qualityJob?.cancel()
        qualityJob = viewModelScope.launch {
            try {
                _uiState.update { st ->
                    if (st.qualityRequestId == requestId) st.copy(isMediaTesting = true, mediaQualityResult = null)
                    else st
                }
                val raw = try {
                    endpointTestService.test(url, probePath)
                } catch (e: CancellationException) {
                    throw e
                }
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
                // 闸 1+2：请求身份与草稿版本任一不符 → 显示与写库同时禁止
                val accepted = current.qualityRequestId == requestId &&
                    AddressTestResultPolicy.shouldApply(current.addressVersion, versionAtRequest)
                val targetsSavedEndpoint = endpointIdAtRequest != null && expectedUrlAtRequest == url
                _uiState.update { st ->
                    if (st.qualityRequestId != requestId) st
                    else st.copy(
                        isMediaTesting = false,
                        mediaQualityResult = if (accepted) result else st.mediaQualityResult,
                    )
                }
                if (accepted && targetsSavedEndpoint && endpointIdAtRequest != null) {
                    serverStore.updateEndpointQuality(
                        serverId = serverId,
                        endpointId = endpointIdAtRequest,
                        expectedUrl = expectedUrlAtRequest,
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
                        "线路质量测试结果未落库（草稿未保存或已被更新） serverId=$serverId",
                    )
                }
            } finally {
                // 取消/异常/正常收尾：仅当仍是本请求时复位 loading（防旧 finally 关新任务 loading）
                _uiState.update { st ->
                    if (st.qualityRequestId == requestId) st.copy(isMediaTesting = false)
                    else st
                }
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
