package com.mediahub.provider.emby.mapper

import com.mediahub.core.network.ApiException
import com.mediahub.provider.api.ProviderException
import java.io.IOException
import kotlinx.serialization.SerializationException

/** Emby HTTP/JSON/IO 失败到统一 ProviderException 的唯一映射点。 */
object EmbyAuthErrorMapper {
    fun map(error: Exception, serverId: String, login: Boolean): ProviderException = when (error) {
        is ApiException -> when {
            login && (error.statusCode == 401 || error.statusCode == 403) ->
                ProviderException.AuthFailed(serverId, "用户名或密码错误")
            else -> ProviderException.Http(
                serverId,
                error.statusCode,
                error.url,
                error.method,
                error.requestId,
            )
        }
        is SerializationException -> ProviderException.Parse(serverId, error)
        is IOException -> ProviderException.Network(serverId, error)
        is ProviderException -> error
        else -> ProviderException.Unknown(serverId, error)
    }
}
