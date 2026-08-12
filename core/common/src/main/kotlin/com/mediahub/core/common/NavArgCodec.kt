package com.mediahub.core.common

import java.util.Base64

/**
 * Navigation 参数编解码：itemId 可能是文件路径（含 '/'），
 * 直接放进路径段会破坏路由，统一用 Base64(URL_SAFE) 传输。
 *
 * 用 java.util.Base64（minSdk 26 起可用）而非 android.util.Base64，
 * 保证纯 JVM 单元测试可直接调用（android.util.Base64 在单测中 not mocked）。
 */
object NavArgCodec {

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder = Base64.getUrlDecoder()

    fun encode(value: String): String =
        urlEncoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    fun decode(value: String): String =
        String(urlDecoder.decode(value), Charsets.UTF_8)
}
