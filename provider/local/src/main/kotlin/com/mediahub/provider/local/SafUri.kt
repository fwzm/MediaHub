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

    fun treeDocId(treeUri: String): String = decode(segmentAfter(treeUri, "tree"))

    fun docId(documentUri: String): String = decode(segmentAfter(documentUri, "document"))

    fun documentUri(treeUri: String, docId: String): String =
        treeUri.trimEnd('/') + "/document/" + encode(docId)

    fun childrenUri(treeUri: String, parentDocId: String): String =
        treeUri.trimEnd('/') + "/document/" + encode(parentDocId) + "/children"

    private fun segmentAfter(uri: String, marker: String): String {
        val idx = uri.indexOf("/$marker/")
        if (idx < 0) return ""
        val rest = uri.substring(idx + marker.length + 2)
        return rest.substringBefore('/')
    }

    private fun encode(s: String): String = s
        .replace("%", "%25")
        .replace("/", "%2F")
        .replace(":", "%3A")
        .replace(" ", "%20")
        .replace("#", "%23")
        .replace("?", "%3F")

    private fun decode(s: String): String = s
        .replace("%3A", ":")
        .replace("%2F", "/")
        .replace("%20", " ")
        .replace("%23", "#")
        .replace("%3F", "?")
        .replace("%25", "%")
}
