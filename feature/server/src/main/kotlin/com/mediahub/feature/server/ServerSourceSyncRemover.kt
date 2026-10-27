package com.mediahub.feature.server

import com.mediahub.core.database.repository.ServerStore
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
 * 返回值语义与 [SourceSyncSourceRemover] 一致：`true` = 这条来源连凭据一起已经不在了；
 * `false` = 本机没有这条来源、或删除链中途失败（调用方计入 refused 并上报原因，不当作写过）。
 * [ServerRemoveHandler.remove] 抛出的异常在这里折成 `false`：一轮同步的某一条失败不该让整轮
 * 抛出——上层需要的是「这条没删掉」而不是一次崩溃；但协程取消必须继续向上传播。
 */
@Singleton
class ServerSourceSyncRemover @Inject constructor(
    private val serverStore: ServerStore,
    private val removeHandler: ServerRemoveHandler,
) : SourceSyncSourceRemover {

    override suspend fun removeSource(serverId: String): Boolean {
        val id = serverId.trim()
        if (id.isEmpty()) return false
        val server = serverStore.getServer(id) ?: return false
        return try {
            removeHandler.remove(server)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            false
        }
    }
}
