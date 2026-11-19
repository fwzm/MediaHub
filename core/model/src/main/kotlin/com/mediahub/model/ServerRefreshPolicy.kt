package com.mediahub.model

/**
 * 判断媒体源配置变化是否需要让已打开的库/详情页重新拉取。
 *
 * 只比较会影响 Provider 连接与浏览结果的字段（类型、账号、启用线路的地址/顺序/主线路标记）；
 * 名称、备注、图标、线路质量测量结果等展示/统计字段变化不得触发重载。
 */
object ServerRefreshPolicy {
    fun requiresReload(previous: MediaServer?, current: MediaServer?): Boolean {
        if (previous == null || current == null) return false
        if (previous.type != current.type) return true
        if (previous.username != current.username) return true
        return connectionSignature(previous) != connectionSignature(current)
    }

    /** 只取会影响连接/浏览的线路字段；按稳定顺序序列化，质量测量值不参与。 */
    private fun connectionSignature(server: MediaServer): List<String> =
        server.endpoints
            .filter { it.enabled }
            .sortedWith(compareBy<ServerEndpoint>({ it.sortOrder }, { it.id }))
            .map { "${it.url}|${it.isPrimary}|${it.sortOrder}" }
}
