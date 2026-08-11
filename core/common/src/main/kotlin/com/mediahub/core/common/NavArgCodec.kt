package com.mediahub.core.common

import android.util.Base64

/**
 * Navigation 参数编解码：itemId 可能是文件路径（含 '/'），
 * 直接放进路径段会破坏路由，统一用 Base64(URL_SAFE) 传输。
 */
object NavArgCodec {

    fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun decode(value: String): String =
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
}
