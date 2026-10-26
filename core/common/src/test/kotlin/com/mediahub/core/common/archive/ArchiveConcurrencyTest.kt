package com.mediahub.core.common.archive

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/** Different malformed local byte arrays must never share an operation's reason. */
class ArchiveConcurrencyTest {
    @Test fun compatibilityDiagnosticBelongsToTheCallingThread() {
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        val failures = ConcurrentLinkedQueue<String>()
        try {
            val jobs = (0 until 2).map { worker -> pool.submit {
                val expected = if (worker == 0) ArchiveError.MAGIC_MISMATCH else ArchiveError.CORRUPT_HEADER
                val input = if (worker == 0) ByteArray(0) else ArchiveHeader.CURRENT_MAGIC.toByteArray(Charsets.US_ASCII)
                assertTrue(ArchiveReader.tryRead(input, null).second == expected)
                barrier.await(5, TimeUnit.SECONDS)
                if (ArchiveReader.lastReason != expected) failures += "worker=$worker diagnostic=${ArchiveReader.lastReason}"
            } }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(failures.toString(), failures.isEmpty())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun concurrentReadersKeepTheirOwnFailureReason() {
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<String>()
        val pool = Executors.newFixedThreadPool(4)
        val magicOnly = ArchiveHeader.CURRENT_MAGIC.toByteArray(Charsets.US_ASCII)
        try {
            val jobs = (0 until 4).map { worker ->
                pool.submit {
                    start.await()
                    val input = if (worker % 2 == 0) ByteArray(0) else magicOnly
                    val expected = if (worker % 2 == 0) ArchiveError.MAGIC_MISMATCH else ArchiveError.CORRUPT_HEADER
                    repeat(30_000) {
                        val result = ArchiveReader.tryRead(input, null)
                        if (result.first != null || result.second != expected) {
                            failures += "worker=$worker expected=$expected actual=${result.second}"
                            return@submit
                        }
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue("Independent tryRead calls shared a failure reason: $failures", failures.isEmpty())
        } finally {
            pool.shutdownNow()
            assertTrue("Owned workers must exit", pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
