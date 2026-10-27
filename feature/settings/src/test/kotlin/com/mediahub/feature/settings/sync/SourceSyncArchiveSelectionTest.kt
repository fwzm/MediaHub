package com.mediahub.feature.settings.sync

import com.mediahub.core.database.sync.SourceSyncDocumentResolution
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSyncArchiveSelectionTest {
    @Test
    fun replacingTheDocumentDuringUnlockCannotLoadTheOldEnvelope() = runTest {
        val loaded = mutableListOf<String>()
        lateinit var controller: SourceSyncArchiveController
        controller = SourceSyncArchiveController(
            port = { _, _ ->
                controller.offer("MHSYNCA1replacement".toByteArray(), "same.mharchive")
                SourceSyncDocumentResolution.Plaintext("old-envelope")
            },
            onUnlocked = { json, _ -> loaded += json },
            onBlocked = { _, _, _ -> },
        )
        controller.offer("MHSYNCA1original".toByteArray(), "same.mharchive")
        controller.unlock("fixture passphrase")
        assertEquals(emptyList<String>(), loaded)
        assertEquals(true, controller.prompt.value.visible)
        assertEquals("MHSYNCA1replacement".length, controller.pendingByteCount)
    }
}
