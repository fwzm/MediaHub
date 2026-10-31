package com.mediahub.feature.server

import com.mediahub.core.database.repository.AccountRepository
import com.mediahub.core.database.repository.ProgressRepository
import com.mediahub.core.database.repository.SubtitleMemoryRepository
import com.mediahub.core.database.repository.ServerRepository
import com.mediahub.core.database.sync.SourceSyncRemovalStep
import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.SessionStoreCleaner
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 删除媒体源的窄接口（Phase 1I 可测性）：ServerEditorViewModel 只依赖本接口，
 * 测试用一行 fake 替代（真实用例级联清理链不需要进入 VM 测试）。
 */
interface ServerRemoveHandler {
    suspend fun remove(server: MediaServer)
}

/**
 * W6-88：删除链的某一步失败。
 *
 * 为什么不是普通的 RuntimeException：级联清理有八步，[step] 说得出卡在哪一步，跨端同步那边才能把
 * 步骤码挂进原因码（`local-source-remove-failed:<step>`）。只抛原始异常等于把「第几步」这条信息丢在
 * 调用栈里，用户看到的永远是一句「删除失败」。
 *
 * 已经做完的步骤不回滚：删除是单向的，重试一次即可；[cause] 保留原始异常便于日志排查。
 */
class RemoveServerFailedException(
    val step: SourceSyncRemovalStep,
    cause: Throwable,
) : Exception("remove-server-failed:" + step.code, cause)

/**
 * 删除媒体源（级联清理，Server Editor 危险区）。
 *
 * 清理八步：server(+endpoints) → account → token → credential → progress → subtitle-memory → 图标 → provider 会话。
 * 若删除的是默认媒体源，ServerRepository.deleteServer 会重选首条为默认（保持最多一个 default 不变式）。
 *
 * 每一步都通过 [runStep] 包一层：失败时抛 [RemoveServerFailedException] 并带上步骤，步骤码表在
 * `core:database` 的 [SourceSyncRemovalStep]（结果页与日志共用同一份权威）。
 *
 * W6-100：失败时把步骤码与原始原因写进日志（logcat 与诊断导出的内存缓冲），与结果页用的是同一份权威；
 * 用户点删除（Server Editor）与跨设备同步触发的删除走的是同一个用例，因此两条路径都有这行诊断。
 * 日志内容一律经 [Logger] 实现脱敏（Token / Cookie / 密码不会进日志），这里只交原始异常，不做字符串拼接。
 */
@Singleton
class RemoveServerUseCase @Inject constructor(
    private val serverRepository: ServerRepository,
    private val progressRepository: ProgressRepository,
    private val subtitleMemoryRepository: SubtitleMemoryRepository,
    private val accountRepository: AccountRepository,
    private val tokenStore: TokenStore,
    private val credentialVault: CredentialVault,
    private val serverIconStore: ServerIconStore,
    private val sessionStoreCleaner: SessionStoreCleaner,
    private val logger: Logger,
) : ServerRemoveHandler {
    override suspend fun remove(server: MediaServer) {
        val id = server.id
        runStep(SourceSyncRemovalStep.SERVERS) { serverRepository.deleteServer(id) }
        runStep(SourceSyncRemovalStep.ACCOUNTS) { accountRepository.deleteForServer(id) }
        runStep(SourceSyncRemovalStep.TOKENS) { tokenStore.clear(id) }
        runStep(SourceSyncRemovalStep.CREDENTIALS) { credentialVault.clear(id) }
        runStep(SourceSyncRemovalStep.PROGRESS) { progressRepository.deleteByServer(id) }
        runStep(SourceSyncRemovalStep.SUBTITLE_MEMORY) { subtitleMemoryRepository.deleteByServer(id) }
        runStep(SourceSyncRemovalStep.ICON) { serverIconStore.remove(id) }
        runStep(SourceSyncRemovalStep.SESSION_STORE) { sessionStoreCleaner.clear(id) }
        logger.i(LogTag.DATABASE, "remove-server-completed:" + id)
    }

    private suspend fun runStep(step: SourceSyncRemovalStep, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            logger.w(LogTag.DATABASE, "remove-server-failed:" + step.code, failure)
            throw RemoveServerFailedException(step, failure)
        }
    }
}
