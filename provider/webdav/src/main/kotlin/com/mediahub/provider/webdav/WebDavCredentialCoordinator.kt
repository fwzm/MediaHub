package com.mediahub.provider.webdav

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WebDAV 凭据协调器（A2-1 返修）：**应用级共享、按 serverId 隔离**的状态机。
 *
 * 为什么必须存在：[WebDavProviderFactory.create] 每次 new 一个
 * [WebDavCredentialStore]；世代表若挂在 store 实例上，重建 handle / 双 handle
 * 并存时各数各的，迟到失败的条件清理会误删较新身份的密码。
 * 协调器由 Hilt 装配为单例，所有 store 实例共享同一张世代表。
 *
 * 线性化点：每个 serverId 一把 [Mutex]，世代的读取/推进与 vault 的读/写/删
 * **在同一次持锁内完成**——条件校验（世代比较）与 vault.remove 之间不存在窗口，
 * savePassword 的增代与写库之间不存在窗口。网络 IO 一律不持锁（锁只覆盖
 * 本地凭据操作）。
 *
 * 边界（如实声明）：这是**进程内**协调，不宣称跨进程事务；跨进程/跨设备的
 * 身份一致性由 PR #18 的 TokenStore restore lease 负责（联合候选中显式对齐，
 * 见 [invalidate]）。
 */
@Singleton
class WebDavCredentialCoordinator @Inject constructor() {

    /** 单个服务器的凭据状态：世代与其锁绑定，杜绝"检查与执行分离"。 */
    class ServerState {
        val mutex = Mutex()
        var generation: Long = 0L
    }

    private val states = ConcurrentHashMap<String, ServerState>()

    private fun state(serverId: String): ServerState =
        states.computeIfAbsent(serverId) { ServerState() }

    /** 在该服务器的凭据锁内执行 [block]；网络等待不得放进 [block]。 */
    suspend fun <T> withServerLock(serverId: String, block: suspend (ServerState) -> T): T {
        val state = state(serverId)
        return state.mutex.withLock { block(state) }
    }

    /** 身份变更事件（如备份恢复替换身份）显式推进世代，使全部在途 handle 失效。 */
    suspend fun invalidate(serverId: String) {
        withServerLock(serverId) { it.generation++ }
    }
}
