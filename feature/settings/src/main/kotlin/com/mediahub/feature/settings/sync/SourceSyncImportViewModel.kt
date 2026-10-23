package com.mediahub.feature.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.common.sync.AndroidSourceTypeMap
import com.mediahub.core.database.repository.ServerRepository
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

    fun loadEnvelope(envelopeJson: String, fileName: String? = null) {
        session.loadEnvelope(envelopeJson, fileName)
    }

    fun preview() {
        viewModelScope.launch { session.preview() }
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

    fun reset() {
        session.reset()
        _verification.value = SourceVerificationViewState()
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

