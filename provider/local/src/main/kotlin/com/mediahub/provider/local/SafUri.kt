package com.mediahub.provider.local

/**
 * SAF 文档树 URI 的纯字符串实现（等价于 DocumentsContract 的 uri 格式约定）：
 *
 * - tree uri：    content://authority/tree/{treeDocId}
 * - document uri：content://authority/tree/{treeDocId}/document/{docId}
 * - children uri：content://authority/tree/{treeDocId}/document/{parentDocId}/children
 *
 * 采用字符串实现而非 android.net.Uri，使核心导航逻辑可 JVM 单测；
 * 生产与测试共用同一路径（真实 ContentProvider 交互需真机/instrumented 验证）。
 */
internal object SafUri {

    private fun parsed(value: String) = java.net.URI(value).also {
        require(it.scheme.equals("content", true) && !it.rawAuthority.isNullOrBlank() &&
            it.rawQuery == null && it.rawFragment == null) { "Invalid document URI" }
    }
    private fun segments(value: String) = parsed(value).rawPath.trim('/').split('/')
    fun validTree(value: String): Boolean = runCatching {
        val parts = segments(value)
        parts.size == 2 && parts[0] == "tree" && decode(parts[1]).isNotBlank()
    }.getOrDefault(false)
    fun sameTree(a: String, b: String): Boolean = runCatching {
        validTree(a) && validTree(b) && parsed(a).rawAuthority == parsed(b).rawAuthority && treeDocId(a) == treeDocId(b)
    }.getOrDefault(false)
    fun contains(treeUri: String, documentUri: String): Boolean = runCatching {
        val parts = segments(documentUri)
        validTree(treeUri) && parsed(treeUri).rawAuthority == parsed(documentUri).rawAuthority &&
            parts.size == 4 && parts[0] == "tree" && parts[2] == "document" &&
            decode(parts[1]) == treeDocId(treeUri) && decode(parts[3]).isNotBlank()
    }.getOrDefault(false)

    fun treeDocId(treeUri: String): String {
        require(validTree(treeUri)) { "Invalid document tree" }
        return decode(segments(treeUri)[1])
    }

    fun docId(documentUri: String): String {
        val parts = segments(documentUri)
        require(parts.size == 4 && parts[0] == "tree" && parts[2] == "document" && parts[3].isNotBlank()) { "Invalid tree document" }
        return decode(parts[3])
    }

    fun documentUri(treeUri: String, docId: String): String {
        require(validTree(treeUri) && docId.isNotBlank()) { "Invalid tree document" }
        return treeUri.trimEnd('/') + "/document/" + encode(docId)
    }

    fun childrenUri(treeUri: String, parentDocId: String): String =
        treeUri.trimEnd('/') + "/document/" + encode(parentDocId) + "/children"

    private fun segmentAfter(uri: String, marker: String): String {
        val idx = uri.indexOf("/$marker/")
        if (idx < 0) return ""
        val rest = uri.substring(idx + marker.length + 2)
        return rest.substringBefore('/')
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun decode(s: String): String = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
}
