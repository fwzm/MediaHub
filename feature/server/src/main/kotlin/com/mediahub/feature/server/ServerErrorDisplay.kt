package com.mediahub.feature.server

/**
 * 文件源错误信息展示（条目 111）。
 *
 * 错误文本会直接出现在卡片上，必须：
 * 1. 去掉服务器 URL，避免把内网地址暴露在截图/共享画面里；
 * 2. 对 token / password 之类的查询参数打码；
 * 3. 折叠换行与多余空白，保证多行展示时可读。
 */
private val URL_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val SECRET_PATTERN = Regex(
    """(?i)\b(token|api[_-]?key|access[_-]?token|password|passwd)=([^&\s]+)""",
)

fun displayServerError(raw: String): String {
    if (raw.isBlank()) return ""
    return raw
        .replace(URL_PATTERN, "<服务器地址>")
        .replace(SECRET_PATTERN) { match -> "${match.groupValues[1]}=<已隐藏>" }
        .replace(Regex("""\s+"""), " ")
        .trim()
}
