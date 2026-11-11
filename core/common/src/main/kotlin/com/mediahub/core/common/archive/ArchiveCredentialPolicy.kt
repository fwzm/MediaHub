package com.mediahub.core.common.archive

import com.mediahub.core.common.sync.SourceSyncEnvelope
import kotlinx.serialization.json.JsonNull

/** An opaque credential block is forbidden even if its capability flag was cleared. */
internal fun SourceSyncEnvelope.hasArchiveCredentialBlocks(): Boolean = sources.any {
    it.secrets != null && it.secrets !is JsonNull
}
