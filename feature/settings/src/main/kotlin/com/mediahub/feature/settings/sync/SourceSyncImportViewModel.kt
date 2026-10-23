package com.mediahub.feature.settings.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediahub.core.common.ClientIdentity
import com.mediahub.core.database.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 跨设备来源导入页的 ViewModel（W6-F）。
 *
 * 只做两件事：把 Session 接到 viewModelScope 上、把生产实现注入进来。
 * 全部流程逻辑在 SourceSyncImportSession（纯 Kotlin，JVM 单测覆盖），这里刻意保持薄。
 *
 * 生产接线：ServerRepository 同时实现 SyncSourceStore（只读，预览用）与 SourceSyncStore（唯一写库入口），
 * deviceId 取 ClientIdentity.deviceId（与信封里的设备标识一致）。
 */
@HiltViewModel
class SourceSyncImportViewModel @Inject constructor(
    serverRepository: ServerRepository,
    clientIdentity: ClientIdentity,
) : ViewModel() {

    private val session = SourceSyncImportSession.production(
        localSources = serverRepository,
        sourceSyncStore = serverRepository,
        deviceId = clientIdentity.deviceId,
    )

    val state: StateFlow<SourceSyncImportState> = session.state

    fun loadEnvelope(envelopeJson: String, fileName: String? = null) {
        session.loadEnvelope(envelopeJson, fileName)
    }

    fun preview() {
        viewModelScope.launch { session.preview() }
    }

    fun decide(key: String, decision: SourceSyncEntryDecision) {
        session.decide(key, decision)
    }

    fun apply() {
        viewModelScope.launch { session.apply() }
    }

    fun reset() {
        session.reset()
    }
}
