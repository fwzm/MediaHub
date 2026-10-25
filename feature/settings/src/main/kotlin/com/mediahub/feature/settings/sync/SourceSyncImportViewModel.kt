package com.mediahub.feature.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.common.sync.AndroidSourceTypeMap
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.database.sync.AndroidSourceSyncService
import com.mediahub.core.security.CredentialVault
import com.mediahub.provider.api.MediaProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 跨设备来源导入页的 ViewModel（W6-F + W6-H）。
 *
 * 只做三件事：把 Session 接到 viewModelScope 上、把生产实现注入进来、
 * 把「导入结果里的每一条来源」映射成 W6-H 的逐源验证目标。
 * 全部流程逻辑在 SourceSyncImportSession 与 SourceVerificationCoordinator（都是纯 Kotlin，JVM 单测覆盖），
 * 这里刻意保持薄。
 *
 * 生产接线：ServerRepository 同时实现 SyncSourceStore（只读，预览用）与 SourceSyncStore（唯一写库入口），
 * deviceId 取 ClientIdentity.deviceId（与信封里的设备标识一致）；
 * 凭据只经 CredentialVault（Keystore），校验只经 provider 层的 MediaAuthProvider。
 */
@HiltViewModel
class SourceSyncImportViewModel @Inject constructor(
    serverRepository: ServerRepository,
    clientIdentity: ClientIdentity,
    credentialVault: CredentialVault,
    providerRegistry: MediaProviderRegistry,
) : ViewModel() {

    private val session = SourceSyncImportSession.production(
        localSources = serverRepository,
        sourceSyncStore = serverRepository,
        deviceId = clientIdentity.deviceId,
    )

    private val verification = SourceVerificationCoordinator(
        credentials = VaultSourceCredentialPort(credentialVault),
        authenticator = ProviderSourceAuthenticator(serverRepository, providerRegistry),
    )

    val state: StateFlow<SourceSyncImportState> = session.state

    /**
     * W6-27：从 Windows 接收。
     *
     * 接收成功只把信封交给 [SourceSyncImportSession.loadEnvelope]（等于"用户手动选了文件"），
     * 预览、逐条允许、应用仍然要用户自己点；失败路径不碰 session 的任何既有数据。
     */
    private val receiveController = SourceSyncReceiveController(
        port = SourceSyncTransferReceivePort(),
        onEnvelope = { envelopeJson, fileName -> session.loadEnvelope(envelopeJson, fileName) },
    )

    /** 接收区块的四态（Idle / Receiving / Received / Rejected / Failed）。 */
    val receiveState: StateFlow<SourceSyncReceiveState> = receiveController.state

    /**
     * W6-36：手机当接收端（监听）。
     *
     * 生产实现固定用 [SourceSyncListenerImportFactory]（网关是 acceptOnly，只解码不写库），
     * 收到信封同样只交给 [SourceSyncImportSession.loadEnvelope]：等于用户手选了文件，
     * 预览、逐条允许、应用仍然要用户自己点。
     */
    private val listenerController = SourceSyncListenerController(
        factory = SourceSyncListenerImportFactory(),
        onEnvelope = { envelopeJson, fileName -> session.loadEnvelope(envelopeJson, fileName) },
    )

    /** 监听区块的六态（Idle / Listening / Receiving / Received / Rejected / Failed）。 */
    val listenState: StateFlow<SourceSyncListenState> = listenerController.state

    /** TLS 能不能开：今天 App 里没有证书下发通道，因此如实为「不可用」。 */
    val listenTls: SourceSyncListenTlsAvailability
        get() = listenerController.tlsAvailability

    // ---- W6-42：发送（Android → Windows） ----

    /**
     * 导出端取生产实现 [AndroidSourceSyncService]。
     *
     * 导出本身会为还没有同步身份的来源派生 id 并回写（与 Windows export 一致），本层不再补任何字段；
     * 没有可用线路、或类型未知的来源会被跳过，条数如实报给界面（[SourceSyncSendExport.skippedCount]）。
     */
    private val sourceSyncService = AndroidSourceSyncService(
        store = serverRepository,
        deviceId = clientIdentity.deviceId,
    )

    /**
     * 发送控制器（W6-42）：只把「本机导出」与「core:common 的传输客户端」接起来。
     *
     * 协议细节一行都不在这里：分帧、认证 tag、TLS 钉扎、回执四码全部由 [SourceSyncTransferClient] 负责，
     * 失败码原样透传；本层只多「端口/指纹/口令还没填好就拒」的四个码。
     */
    private val sendController = SourceSyncSendController(
        exportPort = object : SourceSyncExportPort {
            override suspend fun export(): SourceSyncSendExport {
                val exported = sourceSyncService.export()
                return SourceSyncSendExport(
                    envelopeJson = exported.json,
                    sourceCount = exported.envelope.sources.size,
                    skippedCount = exported.skippedServerIds.size,
                )
            }
        },
        sendPort = SourceSyncTransferSendPort(),
    )

    /** 发送区块的状态（Idle / Sending / Sent / Failed）。 */
    val sendState: StateFlow<SourceSyncSendState> = sendController.state

    /**
     * W6-49：把「一次发送 = 一个可取消的 Job」这层记账单独拿出来（见 [SourceSyncSendLauncher]）。
     *
     * 用 viewModelScope 起的 Job 会随页面销毁自动取消；用户点「取消发送」时调 [cancelSend]，
     * 取消语义全在控制器里：状态回 Idle、不产生任何稳定码、inFlight 立刻释放所以能马上重发。
     */
    private val sendLauncher = SourceSyncSendLauncher(viewModelScope) { form ->
        sendController.send(form)
    }

    /**
     * W6-58：把「一次接收 = 一个可取消的 Job + 一个取消把手」这层记账单独拿出来
     * （见 [SourceSyncReceiveLauncher]）。
     *
     * 与发送那条的唯一区别是取消要**真的关连接**：分块收帧是阻塞 socket 读，
     * 只 cancel() 协程会让界面停在「接收中」直到读超时（缺省 15 秒）。
     */
    private val receiveLauncher = SourceSyncReceiveLauncher(viewModelScope) { form, cancel ->
        receiveController.receive(form, cancel)
    }

    private val _verification = MutableStateFlow(SourceVerificationViewState())

    /** 结果页的逐源验证状态（三态 + 对话框 + 忙碌标记）。 */
    val verificationState: StateFlow<SourceVerificationViewState> = _verification.asStateFlow()

    init {
        viewModelScope.launch {
            session.state.collect { current ->
                _verification.update { view -> view.withSources(targetsOf(current)) }
            }
        }
    }

    /** 手动选文件：顺手清掉上一次的接收结论，避免界面上挂着"已收下信封"的旧回执。 */
    fun loadEnvelope(envelopeJson: String, fileName: String? = null) {
        receiveController.clear()
        listenerController.clearResult()
        session.loadEnvelope(envelopeJson, fileName)
    }

    /**
     * 从 Windows 接收一封信封。
     *
     * 端口为空/非法时 [SourceSyncReceiveController.receive] 直接返回 false（不发请求、不改状态）；
     * 已有一条在飞时同样返回 false，所以连点只会发一次。
     */
    fun receive(form: SourceSyncReceiveForm) {
        receiveLauncher.launch(form)
    }

    /**
     * W6-58：取消正在飞的那一次接收。
     *
     * 没在收时是 no-op（不伪造 Failed、不关任何连接）；正在收时先关连接（阻塞的读立刻返回），
     * 再取消协程，控制器把状态写回 Idle——**取消是用户操作，不是失败，所以没有任何稳定码**。
     */
    fun cancelReceive() {
        receiveLauncher.cancel()
    }

    fun preview() {
        viewModelScope.launch { session.preview() }
    }

    // ---- W6-36：手机当接收端（监听） ----

    /**
     * 开回环监听等 Windows 发过来。
     *
     * 端口留空 = 系统分配；TLS 只在有可用身份证书时才允许（否则控制器给 tls-certificate-unavailable，
     * 连 socket 都不开）。绑 socket 在控制器的 IO 上下文里做，界面线程不碰。
     */
    fun startListening(form: SourceSyncListenForm) {
        viewModelScope.launch { listenerController.start(form) }
    }

    /** 停止监听并释放端口；重复调用无副作用。 */
    fun stopListening() {
        listenerController.stop()
    }

    /** 等一次发送：收到就交给导入页（只进预览，不写库），失败只更新监听区块的状态。 */
    fun awaitListenOnce() {
        viewModelScope.launch { listenerController.awaitOnce() }
    }

    fun decide(key: String, decision: SourceSyncEntryDecision) {
        session.decide(key, decision)
    }

    /** 应用导入，然后对刚落库的每条来源做一次只读探测（有凭据才真的去认证）。 */
    fun apply() {
        viewModelScope.launch {
            session.apply()
            refreshVerification()
        }
    }

    // ---- W6-42：Android 当发送端 ----

    /**
     * 导出本机信封并发给 Windows 的 transfer serve。
     *
     * 端口为空/越界/非数字、开了 TLS 却没填指纹、开了认证却没填口令时，控制器直接返回 false：
     * **不导出、不拨号、不改任何本地数据**。已有一条在飞时同样只发一次（单飞由控制器保证）。
     */
    fun send(form: SourceSyncSendForm) {
        sendLauncher.launch(form)
    }

    /**
     * W6-49：取消正在飞的那一次发送。
     *
     * 没在发时是 no-op（不伪造 Failed、不改状态）；正在发时取消后控制器把状态写回 Idle，
     * 界面回到「能再发一次」的样子——**取消是用户操作，不是失败，所以没有任何稳定码**。
     */
    fun cancelSend() {
        sendLauncher.cancel()
    }

    /** 清掉上一次的发送结论（换页或换表单时用），不影响正在飞的那一次。 */
    fun clearSendResult() {
        sendController.clearResult()
    }

    fun reset() {
        session.reset()
        receiveController.clear()
        listenerController.clearResult()
        receiveLauncher.clear()
        sendLauncher.clear()
        sendController.clearResult()
        _verification.value = SourceVerificationViewState()
    }

    /**
     * 页面销毁时必须关掉监听，否则端口会一直占着（accept 线程也会留着）。
     *
     * 发送端不占端口：连接随 viewModelScope 取消关闭，所以这里只清结论、不做别的。
     */
    override fun onCleared() {
        listenerController.stop()
        // viewModelScope 取消时那两条 Job 自己会收尾（控制器把状态写回 Idle），这里只丢引用。
        receiveLauncher.clear()
        sendLauncher.clear()
        sendController.clearResult()
        super.onCleared()
    }

    // ---- W6-H：逐源补录并验证 ----

    fun openVerificationDialog(serverId: String) {
        _verification.update { it.withDialog(serverId) }
    }

    fun dismissVerificationDialog() {
        _verification.update { it.withDialog(null) }
    }

    /**
     * 保存并验证：密码只在内存里交给协调器，认证通过才写进 Keystore；
     * 无论成败都只把结论（三态 + 原因码）写回界面，来源本身（含 syncSourceId）一律不动。
     */
    fun verify(serverId: String, secret: String) {
        val current = _verification.value
        val source = current.sources.firstOrNull { it.serverId == serverId } ?: return
        if (current.isBusy(serverId)) return
        viewModelScope.launch {
            _verification.update { it.withBusy(serverId) }
            val outcome = verification.verify(source, secret)
            _verification.update { view ->
                view.withOutcome(outcome).withBusy(null).withDialog(null)
            }
        }
    }

    /** 对当前结果页的来源重跑只读探测（无凭据的来源不会发请求）。 */
    suspend fun refreshVerification() {
        val targets = _verification.value.sources
        if (targets.isEmpty()) return
        val outcomes = verification.inspect(targets)
        _verification.update { view -> view.withOutcomes(outcomes) }
    }

    private fun targetsOf(state: SourceSyncImportState): List<SourceVerificationSource> =
        state.applied?.appliedSources.orEmpty().mapNotNull { applied ->
            val serverId = applied.serverId.trim()
            if (serverId.isEmpty()) return@mapNotNull null
            SourceVerificationSource(
                serverId = serverId,
                title = applied.title.trim().ifEmpty { serverId },
                serverType = AndroidSourceTypeMap.toServerType(applied.sourceTypeRaw),
                syncSourceId = applied.syncSourceId,
            )
        }
}
