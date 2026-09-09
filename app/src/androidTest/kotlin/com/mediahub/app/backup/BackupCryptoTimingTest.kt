package com.mediahub.app.backup

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediahub.core.common.backup.BackupCrypto
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.SecretKeyFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 单独运行本类，以取得该 instrumentation 进程首次/第二次生产 KDF 成本。
 * 只允许 -e backupAcceptance isolated 的模拟器；60 秒是诊断截止，不是性能 SLA。
 * Future 的有限等待不代表同步 JCA generateSecret 能响应中断，超时必须保留为失败。
 */
@RunWith(AndroidJUnit4::class)
class BackupCryptoTimingTest {
    @Test
    fun productionKdfFirstAndSecondDerivationsReportWallCpuAndMainResponsiveness() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("isolated", InstrumentationRegistry.getArguments().getString("backupAcceptance"))
        assertTrue("仅允许显式授权的隔离模拟器", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assertFalse("计时控制器不能运行在主线程", Looper.myLooper() == Looper.getMainLooper())
        assertEquals("生产 KDF 参数不可为计时测试降低", 600_000, BackupCrypto.PBKDF2_ITERATIONS)

        val evidence = File(instrumentation.targetContext.getExternalFilesDir(null), "backup-crypto-timing.txt")
        evidence.writeText("pid=${Process.myPid()} hardware=${Build.HARDWARE} api=${Build.VERSION.SDK_INT} " +
            "iterations=600000 bits=256 first-and-second-in-this-process diagnostic-limit-ms=60000 not-performance-SLA\n")
        val password = "AgentB-synthetic-crypto-timing-password".toCharArray()
        val salt = ByteArray(BackupCrypto.PBKDF2_SALT_BYTES) { it.toByte() }
        val ioExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "backup-crypto-timing-io").apply { isDaemon = true }
        }
        var inFlight: Future<Sample>? = null
        var allSamplesCompleted = false
        try {
            for (label in listOf("first", "second")) {
                val started = CountDownLatch(1)
                val deadlineNanos = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(60)
                inFlight = ioExecutor.submit<Sample> {
                    assertFalse("生产派生必须在后台 IO 线程执行", Looper.myLooper() == Looper.getMainLooper())
                    val wallStart = SystemClock.elapsedRealtimeNanos()
                    val cpuStart = Debug.threadCpuTimeNanos()
                    started.countDown()
                    val derived = BackupCrypto.deriveKey(password, salt, BackupCrypto.PBKDF2_ITERATIONS, 256)
                    val cpuNanos = Debug.threadCpuTimeNanos() - cpuStart
                    val wallNanos = SystemClock.elapsedRealtimeNanos() - wallStart
                    // 派生后才查询默认 provider，避免测量前预热 factory 初始化。
                    val factory = SecretKeyFactory.getInstance(ALGORITHM)
                    assertEquals(ALGORITHM, factory.algorithm)
                    val provider = factory.provider.name
                    val encoded = derived.key.encoded
                    try {
                        assertEquals(ALGORITHM, derived.params.algorithm)
                        assertEquals(600_000, derived.params.iterations)
                        assertEquals(256, derived.params.keyLengthBits)
                        assertEquals("AES", derived.key.algorithm)
                        assertEquals("AES-256 key 长度", 32, encoded.size)
                        assertTrue(cpuNanos >= 0L)
                        assertTrue(wallNanos > 0L)
                        Sample(label, wallNanos, cpuNanos, provider, Thread.currentThread().name)
                    } finally {
                        encoded.fill(0)
                    }
                }
                assertTrue("后台派生须在有界时间内开始", started.await(5, TimeUnit.SECONDS))
                val duringPulse = mainThreadPulse()
                report(evidence, "$label main-pulse-during=$duringPulse")
                assertTrue("KDF 执行时主线程 pulse 必须在 1000ms 内响应", duringPulse)
                val remainingNanos = deadlineNanos - SystemClock.elapsedRealtimeNanos()
                assertTrue("计时任务的诊断截止已到", remainingNanos > 0L)
                val sample = try {
                    inFlight.get(remainingNanos, TimeUnit.NANOSECONDS)
                } catch (timeout: TimeoutException) {
                    report(evidence, "$label diagnostic-timeout-ms=60000; interrupt-requested; JCA-cancellation-unproven")
                    inFlight.cancel(true)
                    throw AssertionError("生产 KDF 未在单次 60 秒诊断窗口内返回；这不是性能 SLA，也不能据此宣称死锁", timeout)
                }
                val afterPulse = mainThreadPulse()
                report(evidence, "${sample.label} wall-ns=${sample.wallNanos} thread-cpu-ns=${sample.cpuNanos} " +
                    "provider=${sample.provider} thread=${sample.threadName} main-pulse-after=$afterPulse")
                assertTrue("派生结束后主线程 pulse 必须在 1000ms 内响应", afterPulse)
            }
            allSamplesCompleted = true
        } finally {
            inFlight?.cancel(true)
            password.fill('\u0000')
            salt.fill(0)
            ioExecutor.shutdownNow()
            val stopped = ioExecutor.awaitTermination(1, TimeUnit.SECONDS)
            Log.i(TAG, "io-executor-terminated=$stopped; no-key-material-recorded")
            // 若原先超时，不用清理失败覆盖首次失败；JCA 提前终止能力仍未证明。
            if (allSamplesCompleted) assertTrue("计时后台线程必须释放", stopped)
        }
    }

    private fun mainThreadPulse(): Boolean {
        val pulse = CountDownLatch(1)
        val callback = Runnable { pulse.countDown() }
        val handler = Handler(Looper.getMainLooper())
        if (!handler.post(callback)) return false
        return try {
            pulse.await(1, TimeUnit.SECONDS)
        } finally {
            handler.removeCallbacks(callback)
        }
    }

    private fun report(file: File, line: String) {
        Log.i(TAG, line)
        file.appendText("$line\n")
    }

    private data class Sample(
        val label: String,
        val wallNanos: Long,
        val cpuNanos: Long,
        val provider: String,
        val threadName: String,
    )

    private companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val TAG = "BackupCryptoTiming"
    }
}
