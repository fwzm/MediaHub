package com.mediahub.core.common

import java.util.UUID

/** 本地实体 id 生成（服务器条目、账号条目等）。 */
object IdGenerator {
    fun newId(prefix: String = ""): String {
        val uuid = UUID.randomUUID().toString().replace("-", "").take(16)
        return if (prefix.isBlank()) uuid else "${prefix}_$uuid"
    }
}
