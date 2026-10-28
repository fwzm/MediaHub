package com.mediahub.feature.server

import com.mediahub.core.database.repository.ServerStore
import com.mediahub.core.database.sync.SourceSyncRemovalCodes
import com.mediahub.core.database.sync.SourceSyncRemoveOutcome
import com.mediahub.core.database.sync.SourceSyncSourceRemover
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * W6-79：跨设备同步里「对端声明删除」的生产实现。
 *
 * 权威行为在 Windows `ServerManager.RemoveRemoteSourceAsync`：找到本机那条来源，然后**走完整删除链**
 * ——不只是删 servers 行，还要清掉凭据柜里的 token、账号行、进度与字幕记忆、图标与会话。
 * 判据原话是「a 『deleted』 source whose token is still on disk is not deleted」，所以这里
 * 复用 [ServerRemoveHandler]（[RemoveServerUseCase]）而不是直接调 `ServerRepository.deleteServer`。
 *
 * W6-88：返回值从 Boolean 换成 [SourceSyncRemoveOutcome]，把三种落点分开——本机没有（[SourceSyncRemoveOutcome.Missing]）、
 * 删干净了（[SourceSyncRemoveOutcome.Removed]）、删到一半失败（[SourceSyncRemoveOutcome.Failed]）。删除链抛
 * [RemoveServerFailedException] 时把步骤码挂进原因码（`local-source-remove-failed:<step>`），因为「凭据可能还在」
 * 这句话对用户不够：他要知道是重试删除还是去来源页手动清。其它异常折成不带步骤的失败码。
 * [ServerRemoveHandler.remove] 抛出的异常在这里折成结果而不向上冒：一轮同步的某一条失败不该让整轮抛出；
 * 但协程取消必须继续向上传播。
 */
@Singleton
class ServerSourceSyncRemover @Inject constructor(
    private val serverStore: ServerStore,
    private val removeHandler: ServerRemoveHandler,
) : SourceSyncSourceRemover {

    override suspend fun removeSource(serverId: String): SourceSyncRemoveOutcome {
        val id = serverId.trim()
        if (id.isEmpty()) return SourceSyncRemoveOutcome.Missing
        val server = serverStore.getServer(id) ?: return SourceSyncRemoveOutcome.Missing
        return try {
            removeHandler.remove(server)
            SourceSyncRemoveOutcome.Removed
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: RemoveServerFailedException) {
            SourceSyncRemoveOutcome.Failed(SourceSyncRemovalCodes.failedAt(failure.step))
        } catch (failure: Exception) {
            SourceSyncRemoveOutcome.Failed(SourceSyncRemovalCodes.FAILED)
        }
    }
}
