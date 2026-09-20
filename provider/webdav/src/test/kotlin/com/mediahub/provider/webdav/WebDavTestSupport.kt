package com.mediahub.provider.webdav

import com.mediahub.core.logging.StdoutLogger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.SecretStorage
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType

/** 内存 SecretStorage（JVM 测试用；Keystore 仅在设备上）。 */
internal class FakeSecretStorage : SecretStorage {
    private val map = mutableMapOf<String, String>()
    override suspend fun put(key: String, value: String) {
        map[key] = value
    }

    override suspend fun get(key: String): String? = map[key]
    override suspend fun remove(key: String) {
        map.remove(key)
    }

    override suspend fun contains(key: String): Boolean = map.containsKey(key)
}

/** 组装一套真实（非替身）Provider 依赖，供 JVM 协议测试使用。 */
internal class WebDavTestStack(val baseUrl: String, val username: String? = "alice") {
    val logger = StdoutLogger()
    val storage = FakeSecretStorage()
    val tokenStore = TokenStore(storage)
    val vault = CredentialVault(storage)
    val credentialStore = WebDavCredentialStore(vault)
    private val http = HttpClientFactory(logger)
    val mediaHttpClient = MediaHttpClient(http.mediaClient(), logger)
    val api = WebDavApi(SERVER_ID, mediaHttpClient, logger)

    val server = MediaServer(
        id = SERVER_ID,
        name = "测试 WebDAV",
        type = ServerType.WEBDAV,
        baseUrl = baseUrl,
        username = username,
        createdAtEpochMs = 0,
    )

    val session = WebDavSession(server, credentialStore)

    suspend fun storePassword(password: String = PASSWORD) {
        vault.save(SERVER_ID, CredentialVault.CredentialKind.PASSWORD, password)
    }

    suspend fun storedPassword(): String? =
        vault.read(SERVER_ID, CredentialVault.CredentialKind.PASSWORD)

    fun browseProvider() = WebDavBrowseProvider(server, api, session)

    fun playbackProvider() = WebDavPlaybackProvider(server, session, logger)

    fun authProvider() = WebDavAuthProvider(server, api, credentialStore, tokenStore, logger)

    val expectedBasicHeader: String
        get() = WebDavAuth.basicHeader(username ?: "", PASSWORD)

    companion object {
        const val SERVER_ID = "s1"
        const val PASSWORD = "p@ss word"
    }
}

/** RFC 4918 `multistatus` 夹具构造。 */
internal object WebDavFixtures {

    fun multistatus(vararg responses: String): String {
        val body = responses.joinToString(separator = "")
        return """<?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">$body</D:multistatus>""".trimIndent()
    }

    /** 带 `displayname` 的集合（目录）。 */
    fun collection(href: String, displayName: String? = null): String {
        val name = displayName?.let { "<D:displayname>$it</D:displayname>" }.orEmpty()
        return """<D:response>
            <D:href>$href</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype><D:collection/></D:resourcetype>
                $name
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>"""
    }

    /** 普通文件；[with404Propstat] 为 true 时把属性放进 404 propstat（必须被丢弃）。 */
    fun file(
        href: String,
        length: Long? = null,
        displayName: String? = null,
        contentType: String? = null,
        with404Propstat: Boolean = false,
    ): String {
        val name = displayName?.let { "<D:displayname>$it</D:displayname>" }.orEmpty()
        val size = length?.let { "<D:getcontentlength>$it</D:getcontentlength>" }.orEmpty()
        val type = contentType?.let { "<D:getcontenttype>$it</D:getcontenttype>" }.orEmpty()
        val status = if (with404Propstat) "HTTP/1.1 404 Not Found" else "HTTP/1.1 200 OK"
        return """<D:response>
            <D:href>$href</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype/>
                $size
                $type
                $name
              </D:prop>
              <D:status>$status</D:status>
            </D:propstat>
          </D:response>"""
    }

    /** 使用非 `D` 前缀 + 默认命名空间变体，验证解析与前缀无关。 */
    fun prefixedFile(href: String, length: Long): String = """<x:response xmlns:x="DAV:">
        <x:href>$href</x:href>
        <x:propstat>
          <x:prop>
            <x:resourcetype/>
            <x:getcontentlength>$length</x:getcontentlength>
          </x:prop>
          <x:status>HTTP/1.1 200 OK</x:status>
        </x:propstat>
      </x:response>"""
}
