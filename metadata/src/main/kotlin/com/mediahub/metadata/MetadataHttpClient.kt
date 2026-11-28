package com.mediahub.metadata

/**
 * 元数据源的 HTTP 传输抽象。
 *
 * 与 `core:network` 的 ApiClient 分离：metadata 是纯 JVM 模块，不依赖 Android 网络栈；
 * 测试用 MockWebServer 注入本地 URL，生产调用方负责传入真实 baseUrl 与密钥。
 */
interface MetadataHttpClient {

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String
}
