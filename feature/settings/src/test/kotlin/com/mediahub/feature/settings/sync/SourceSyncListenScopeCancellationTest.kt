package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncCancellation
import com.mediahub.core.common.sync.SourceSyncListenerStart
import com.mediahub.core.common.sync.SourceSyncTransferListener
import com.mediahub.core.common.sync.SourceSyncTransferClient
import com.mediahub.core.common.sync.SourceSyncListenerTransfer
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real accepted loopback socket; no media account, external peer or database. */
class SourceSyncListenScopeCancellationTest {
    @Test fun scopeCancellationAfterClearClosesTheReadAndKeepsTheListenerOpen() {
        val opened = SourceSyncTransferListener.open(port = 0, readTimeoutMs = 10_000)
        assertTrue(opened is SourceSyncListenerStart.Started)
        val listener = (opened as SourceSyncListenerStart.Started).listener
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val signal = AtomicReference<SourceSyncCancellation?>()
        val reader = AtomicReference<Thread?>()
        val eof = CountDownLatch(1)
        val observer = Executors.newSingleThreadExecutor()
        val peer = Socket("127.0.0.1", listener.port)
        try {
            val launcher = SourceSyncListenLauncher(scope) { cancel ->
                signal.set(cancel)
                withContext(Dispatchers.IO) {
                    reader.set(Thread.currentThread())
                    listener.receiveOnce(cancel = cancel)
                }
                true
            }
            observer.submit {
                while (peer.getInputStream().read() != -1) { /* listener never replies */ }
                eof.countDown()
            }
            assertTrue(launcher.launch())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            fun reading() = reader.get()?.stackTrace?.any {
                it.className == "com.mediahub.core.common.sync.SourceSyncTls" &&
                    it.methodName == "probePlaintext"
            } == true
            while (!reading() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("Must reach the real accepted-socket read before cancellation", reading())
            launcher.clear() // Disposal drops references before its owner Job is cancelled.
            scope.cancel()
            assertTrue("Scope cancellation must unblock before the10s socket timeout", eof.await(1, TimeUnit.SECONDS))
            assertTrue("The captured handle must be cancelled even after clear", signal.get()!!.isCancelled)
            assertFalse("Cancelling one wait must not close the reusable listener", listener.isClosed)
            runBlocking { withTimeout(2_000) { scope.coroutineContext[Job]!!.join() } }
            val envelope = ByteArray(128) { it.toByte() }
            val sent = observer.submit {
                SourceSyncTransferClient().sendTo("127.0.0.1", listener.port, envelope)
            }
            val received = listener.receiveOnce(cancel = SourceSyncCancellation())
            assertTrue("The same listener must accept a later transfer", received is SourceSyncListenerTransfer.Received)
            assertArrayEquals(envelope, (received as SourceSyncListenerTransfer.Received).envelope)
            sent.get(3, TimeUnit.SECONDS)
        } finally {
            signal.get()?.cancel()
            scope.cancel()
            peer.close()
            listener.close()
            observer.shutdownNow()
        }
    }
}
