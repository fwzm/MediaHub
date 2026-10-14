package com.mediahub.player.mpv

import android.content.Context
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.HttpClientFactory
import com.mediahub.player.engine.PlaybackEngineCreator
import com.mediahub.player.engine.PlaybackEnginePort
import kotlinx.coroutines.CoroutineScope

/** mpv 引擎工厂（U2 spike：强制 EngineKind.MPV；U3 起由 AUTO selector 决定用哪个）。 */
class MpvPlaybackEngineCreator(
    private val context: Context,
    private val logger: Logger,
    private val httpClientFactory: HttpClientFactory,
    /** M2（ADR-044）：设备 HDR 证据；默认未知 → 不下发色调映射选项。 */
    private val hdrEvidence: DeviceHdrEvidenceProvider = DeviceHdrEvidenceProvider { null },
) : PlaybackEngineCreator {
    override fun create(scope: CoroutineScope): PlaybackEnginePort =
        MpvPlaybackEngine(context, logger, scope, httpClientFactory, hdrEvidence)
}