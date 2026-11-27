package com.mediahub.metadata

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [MetadataHttpClient] 的 OkHttp 实现。
 *
 * 采用 enqueue + `invokeOnCancellation → Call.cancel` 桥接（与 `core:network.ApiClient`
 * 同款约定）：协程取消立即打断在途请求，不阻塞调用线程。
 */
class OkHttpMetadataHttpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : MetadataHttpClient {

    override suspend fun get(url: String, headers: Map<String, String>): String =
        suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder().url(url).get()
            headers.forEach { (name, value) -> builder.header(name, value) }
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val outcome = try {
                        response.use {
                            if (it.isSuccessful) {
                                Result.success(it.body?.string().orEmpty())
                            } else {
                                Result.failure(
                                    MetadataException(it.code, "元数据请求失败（HTTP ${it.code}）"),
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Result.failure(MetadataException(null, "元数据响应读取失败", e))
                    }
                    if (!continuation.isActive) return
                    outcome.fold(continuation::resume, continuation::resumeWithException)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(MetadataException(null, "元数据网络请求失败", e))
                }
            })
        }
}
