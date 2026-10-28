package com.mediahub.feature.settings.sync

import com.mediahub.core.database.sync.SourceSyncArchiveArtifact
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceSyncArchiveExportCancellationTest {
    @Test
    fun dismissDuringPreparationDiscardsTheLateArtifact() = runTest {
        val result = CompletableDeferred<SourceSyncArchiveArtifact>()
        val controller = SourceSyncArchiveExportController({ result.await() }, { _, _ -> error("no write") })
        controller.request()
        val job = launch { controller.prepare("fixture passphrase") }
        runCurrent()
        controller.dismiss()
        result.complete(SourceSyncArchiveArtifact.Plain("fixture", 1, 0))
        job.join()
        assertEquals(SourceSyncArchiveExportState.Idle, controller.state.value)
        assertEquals(0, controller.pendingByteCount)
    }

    @Test
    fun repeatedPrepareWhileSuspendedOnlyExportsOnce() = runTest {
        val result = CompletableDeferred<SourceSyncArchiveArtifact>()
        var calls = 0
        val controller = SourceSyncArchiveExportController({ calls++; result.await() }, { _, _ -> error("no write") })
        controller.request()
        val first = launch { controller.prepare("") }
        runCurrent()
        val second = launch { controller.prepare("") }
        runCurrent()
        result.complete(SourceSyncArchiveArtifact.Plain("fixture", 1, 0))
        first.join(); second.join()
        assertEquals(1, calls)
    }

    @Test
    fun cancelledSaveDoesNotPublishSuccessIntoANewRequest() = runTest {
        val written = CompletableDeferred<SourceSyncArchiveWriteResult>()
        val controller = SourceSyncArchiveExportController(
            { SourceSyncArchiveArtifact.Plain("fixture", 1, 0) }, { _, _ -> written.await() },
        )
        controller.request(); controller.prepare("")
        val job = launch { controller.save("content://fixture/archive") }
        runCurrent()
        controller.saveCancelled()
        controller.request()
        written.complete(SourceSyncArchiveWriteResult.Written)
        job.join()
        assertTrue(controller.state.value is SourceSyncArchiveExportState.AwaitingPassphrase)
    }

    @Test
    fun coroutineCancellationReleasesThePreparingStateForRetry() = runTest {
        var calls = 0
        val controller = SourceSyncArchiveExportController(
            { if (++calls == 1) throw CancellationException("fixture") else SourceSyncArchiveArtifact.Plain("fixture", 1, 0) },
            { _, _ -> error("no write") },
        )
        controller.request()
        try { controller.prepare("") } catch (_: CancellationException) { }
        controller.prepare("")
        assertEquals(2, calls)
        assertTrue(controller.state.value is SourceSyncArchiveExportState.Ready)
    }
}
