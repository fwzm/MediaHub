package com.mediahub.player.engine

/**
 * 引擎失败指纹历史（U3-A）。
 *
 * 记录"Media3 已证明失败"的 CompatibilitySignature，跨进程持久化；
 * AUTO 模式下同签名后续直接选 mpv。
 *
 * 实现：core:database 的 DataStore 版（应用）；内存版（测试）。
 */
interface EnginePreferenceHistory {
    /** 当前已知 Media3 失败指纹（内存缓存快照，非挂起）。 */
    fun mpvPreferredSignatures(): Set<String>

    /** 记录一次 Media3 失败指纹（幂等）。 */
    suspend fun recordMedia3Failure(signatureKey: String)
}

/** 内存实现（单测用）。 */
class InMemoryEnginePreferenceHistory : EnginePreferenceHistory {
    private val signatures = mutableSetOf<String>()
    override fun mpvPreferredSignatures(): Set<String> = signatures.toSet()
    override suspend fun recordMedia3Failure(signatureKey: String) {
        signatures += signatureKey
    }
}
