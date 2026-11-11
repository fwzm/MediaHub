package com.mediahub.feature.settings.sync

import com.mediahub.core.common.sync.SourceSyncCancellation
import com.mediahub.core.common.sync.SourceSyncTransferClient
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real blocked read, not a suspend-only receive double. Never reaches a real account. */
class SourceSyncReceiveScopeCancellationTest {
    @Test fun scopeCancellationClosesTheBlockedSocketBeforeItsReadTimeout() {
        val listener = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = CountDownLatch(1); val eof = CountDownLatch(1)
        val peer = AtomicReference<Socket?>(); val signal = AtomicReference<SourceSyncCancellation?>()
        val worker = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            worker.submit {
                val socket = listener.accept(); peer.set(socket); accepted.countDown()
                try {
                    val bytes = ByteArray(256)
                    while (socket.getInputStream().read(bytes) != -1) { /* no reply: client blocks */ }
                    eof.countDown()
                } catch (_: java.io.IOException) { }
            }
            val client = SourceSyncTransferClient(connectTimeoutMs = 2_000, readTimeoutMs = 10_000)
            val launcher = SourceSyncReceiveLauncher(scope) { _, cancel ->
                signal.set(cancel)
                withContext(Dispatchers.IO) {
                    client.receiveFrom("127.0.0.1", listener.localPort, cancel = cancel)
                }
                false
            }
            assertTrue(launcher.launch(SourceSyncReceiveForm(host = "127.0.0.1", port = listener.localPort.toString())))
            assertTrue("Controlled TCP connection must actually be accepted", accepted.await(3, TimeUnit.SECONDS))
            scope.cancel()
            assertTrue("Scope cancellation must close the actual socket without waiting10s for read timeout",
                eof.await(1, TimeUnit.SECONDS))
        } finally {
            signal.get()?.cancel(); scope.cancel()
            peer.get()?.close(); listener.close(); worker.shutdownNow()
        }
    }
}
