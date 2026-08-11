package com.mediahub.provider.api

import com.mediahub.model.MediaUser

/** 认证结果。 */
sealed interface AuthResult {
    data class Success(val user: MediaUser) : AuthResult
    data class Failure(val error: ProviderException) : AuthResult
}
