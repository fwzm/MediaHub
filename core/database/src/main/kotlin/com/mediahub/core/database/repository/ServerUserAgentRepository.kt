package com.mediahub.core.database.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 按服务器作用域的自定义 User-Agent 持久化（M0-A 闭环 / ADR-045）。
 *
 * - 与全局播放偏好**分开存储**（独立 DataStore 文件），避免偏好接口被服务器维度污染；
 * - 读取侧提供**同步快照**：OkHttp 拦截器在请求线程上必须能同步取值，
 *   不允许在拦截器里做 I/O 或 suspend；
 * - 服务器删除 / 备份恢复导致 serverId 变化时由 [purgeOrphans] 清理孤儿配置，
 *   保证**身份代际**语义：旧身份的 UA 配置不会被新身份复用；
 * - 本层不做语法校验：合法性由写入方（UI 走 core:network 的 UserAgentValidator）
 *   与读取方（UserAgentPolicy 解析时再次校验）共同保证。即使存储里残留非法值，
 *   也只会退化为默认 UA，不会把非法值发给服务器。
 */
interface ServerUserAgentRepository {
    /** 当前生效快照（serverId → 自定义 UA）。线程安全，供拦截器同步读取。 */
    fun snapshot(): Map<String, String>

    /** 写入 / 清除某服务器的自定义 UA；[userAgent] 为 null 或空白表示恢复默认。 */
    suspend fun set(serverId: String, userAgent: String?)

    /** 清理不再存在的服务器配置（删除账号 / 恢复后身份变化）。返回清理条数。 */
    suspend fun purgeOrphans(existingServerIds: Set<String>): Int
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

    @Volatile
    private var cached: Map<String, String> = emptyMap()

    /** 快照刷新与孤儿清理都跟随 DataStore/服务器表流动；失败不得影响启动。 */
    init {
        scope.launch {
            runCatching {
                dataStore.data.collect { prefs -> cached = readSnapshot(prefs) }
            }
        }
        scope.launch {
            runCatching {
                serverStore.observeServers().collectLatest { servers -> onServersChanged(servers) }
            }
        }
    }

    override fun snapshot(): Map<String, String> = cached

    /**
     * 写透缓存：`edit` 返回写入后的 Preferences，直接据此刷新快照。
     *
     * 这样"保存设置 → 下一次请求生效"**不依赖** DataStore 流回调的调度时机，
     * 消除"suspend 写入已返回、快照仍是旧值"的竞态窗口；流回调只作为
     * 外部变更（多进程/恢复）的兜底刷新。
     */
    override suspend fun set(serverId: String, userAgent: String?) {
        val id = serverId.trim()
        require(id.isNotEmpty()) { "serverId must not be blank" }
        val value = userAgent?.trim()?.takeIf { it.isNotEmpty() }
        val updated = dataStore.edit { prefs ->
            val key = stringPreferencesKey(KEY_PREFIX + id)
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
        cached = readSnapshot(updated)
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
        cached = readSnapshot(updated)
        return removed
    }

    /**
     * 服务器表变化的统一入口（internal 供 JVM 单测确定性驱动）：
     * 删除服务器 / 备份恢复导致 serverId 变化时，清理不再存在的身份配置。
     */
    internal suspend fun onServersChanged(servers: List<com.mediahub.model.MediaServer>) {
        // 空列表**不做**清理：observeServers() 的首次发射可能只是"服务器表尚未加载"，
        // 若据此清理会把用户已保存的自定义 UA 全部误删（测试已捕获该缺陷）。
        // 孤儿配置本身无害（策略只按已解析到的 serverId 查表），
        // 真正的删除由非空列表的差异清理覆盖，或由调用方显式调用 purgeOrphans。
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
