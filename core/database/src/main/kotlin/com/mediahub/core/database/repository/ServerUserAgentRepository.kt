package com.mediahub.core.database.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.mediahub.model.MediaServer

/**
 * 按服务器作用域的自定义 User-Agent 持久化（M0-A / ADR-045）。
 *
 * 语义要点（对应 M0-A1 的三项边界修复）：
 * 1. **加载状态显式化**：`loaded` 表示已从磁盘读到首份快照。拦截器路径不得执行 DataStore I/O，
 *    只能通过 [snapshotBlocking] 做**有界等待**（IO 由专有协程完成）；
 * 2. **删除驱动清理**：孤儿清理只由"已确认的删除"与"已建立基线后的服务器表差异"驱动，
 *    绝不因为"列表为空"就清空用户配置（首次发射/瞬时空表都可能是未加载完成）；
 * 3. **写透 + 序号防回退**：`set/purge` 立即刷新快照并递增写序号；较旧的 Flow 发射
 *    若发现期间发生过写入则**丢弃**，不会覆盖更新的状态。
 */
interface ServerUserAgentRepository {
    /** 是否已完成首次从磁盘加载。 */
    val loaded: Boolean

    /** 同步快照（serverId → 自定义 UA）。未加载完成时为空，见 [snapshotBlocking]。 */
    fun snapshot(): Map<String, String>

    /**
     * 有界等待首次加载完成并返回快照。**不做 I/O**，只等待专有协程完成读取；
     * 超时返回当前（可能为空的）快照，由调用方决定是否回落默认值。
     */
    fun snapshotBlocking(timeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS): Map<String, String>

    /** 挂起版等待（供可挂起的调用方使用）。返回是否已加载完成。 */
    suspend fun awaitLoaded(timeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS): Boolean

    /** 写入 / 清除某服务器的自定义 UA；null 或空白表示恢复默认。 */
    suspend fun set(serverId: String, userAgent: String?)

    /** 清理不再存在的服务器配置（服务器表差异）。返回清理条数。 */
    suspend fun purgeOrphans(existingServerIds: Set<String>): Int

    /** 清理单个服务器（删除账号 / 退出登录等**已确认**的删除路径）。 */
    suspend fun purge(serverId: String)

    companion object {
        const val DEFAULT_LOAD_TIMEOUT_MS: Long = 300L
    }
}

private val Context.serverUserAgentDataStore by preferencesDataStore(name = "server_user_agent")

@Singleton
class DataStoreServerUserAgentRepository(
    private val serverStore: ServerStore,
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : ServerUserAgentRepository {

    @Inject
    constructor(
        serverStore: ServerStore,
        @ApplicationContext context: Context,
    ) : this(
        serverStore = serverStore,
        dataStore = context.serverUserAgentDataStore,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val lock = Any()
    private val loadLatch = CountDownLatch(1)

    @Volatile
    private var cached: Map<String, String> = emptyMap()

    @Volatile
    private var loadedFlag: Boolean = false

    /** 进程内本地权威写入次数：>0 后 Flow 发射不再覆盖 [cached]（避免过期发射回退新值）。 */
    private var localWrites: Long = 0L

    /** 服务器表基线是否已建立（首次发射只建立基线，不做清理）。 */
    private var serversBaselineEstablished: Boolean = false

    override val loaded: Boolean get() = loadedFlag

    init {
        scope.launch {
            runCatching {
                dataStore.data.collect { prefs -> applyFlowSnapshot(prefs) }
            }
        }
        scope.launch {
            runCatching {
                serverStore.observeServers().collectLatest { servers -> onServersChanged(servers) }
            }
        }
    }

    override fun snapshot(): Map<String, String> = cached

    override fun snapshotBlocking(timeoutMs: Long): Map<String, String> {
        if (!loadedFlag) {
            // 只等信号，不在这里做 I/O：读取由 init 中的 IO 协程完成。
            runCatching { loadLatch.await(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) }
        }
        return cached
    }

    override suspend fun awaitLoaded(timeoutMs: Long): Boolean {
        if (loadedFlag) return true
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) {
            while (!loadedFlag) kotlinx.coroutines.yield()
            true
        } ?: loadedFlag
    }

    override suspend fun set(serverId: String, userAgent: String?) {
        val id = serverId.trim()
        require(id.isNotEmpty()) { "serverId must not be blank" }
        val value = userAgent?.trim()?.takeIf { it.isNotEmpty() }
        val updated = dataStore.edit { prefs ->
            val key = stringPreferencesKey(KEY_PREFIX + id)
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
        synchronized(lock) {
            localWrites += 1
            cached = readSnapshot(updated)
        }
    }

    override suspend fun purgeOrphans(existingServerIds: Set<String>): Int {
        val keep = existingServerIds.map { it.trim() }.toSet()
        var removed = 0
        val updated = dataStore.edit { prefs ->
            val orphans = prefs.asMap().keys
                .filter { it.name.startsWith(KEY_PREFIX) }
                .filterNot { it.name.removePrefix(KEY_PREFIX) in keep }
            orphans.forEach { key ->
                prefs.remove(key)
                removed += 1
            }
        }
        synchronized(lock) {
            localWrites += 1
            cached = readSnapshot(updated)
        }
        return removed
    }

    override suspend fun purge(serverId: String) {
        val id = serverId.trim()
        if (id.isEmpty()) return
        val updated = dataStore.edit { prefs -> prefs.remove(stringPreferencesKey(KEY_PREFIX + id)) }
        synchronized(lock) {
            localWrites += 1
            cached = readSnapshot(updated)
        }
    }

    /**
     * Flow 发射的处理。
     *
     * 并发语义：进程内一旦发生过**权威写入**（set/purge），`cached` 以写入结果为准，
     * 后续 Flow 发射只用于确认加载完成，**不再覆盖**快照 —— 这样"较旧的发射晚到"
     * 不可能把新值回退成旧值（测试用可控 DataStore 注入过期发射验证）。
     * 首次加载（尚无任何写入）仍由 Flow 填充快照。
     *
     * 多进程/外部改动不在支持范围（本应用单进程，且恢复流程走应用自身写入）。
     */
    private fun applyFlowSnapshot(prefs: Preferences) {
        val snapshot = readSnapshot(prefs)
        synchronized(lock) {
            if (localWrites == 0L) cached = snapshot
        }
        loadedFlag = true
        loadLatch.countDown()
    }

    /**
     * 服务器表变化：
     * - 首次发射只建立基线（此时可能仍在加载，且空表不等于"没有服务器"）；
     * - 建立基线后，仅在**非空**列表下按差异清理；空表一律不动（删除最后一台由
     *   [purge] 在确认删除路径上处理）。
     */
    internal suspend fun onServersChanged(servers: List<MediaServer>) {
        if (!serversBaselineEstablished) {
            serversBaselineEstablished = true
            return
        }
        if (servers.isEmpty()) return
        purgeOrphans(servers.map { it.id }.toSet())
    }

    private fun readSnapshot(prefs: Preferences): Map<String, String> =
        prefs.asMap().entries
            .filter { it.key.name.startsWith(KEY_PREFIX) }
            .mapNotNull { (key, value) ->
                (value as? String)?.takeIf { it.isNotBlank() }?.let { key.name.removePrefix(KEY_PREFIX) to it }
            }
            .toMap()

    internal companion object {
        const val KEY_PREFIX = "user_agent."
    }
}
