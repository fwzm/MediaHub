# System media-key routing fixture - 2026-10-25

The first real shell-Pause assertion timed out on both API32 and API36 at20966d05. Each emulator report contains90 passes,1 failure and1 expected capability skip; the2415 unique JVM results contain2392 passes and23 gated peer skips. Build/unit/lint passed but final visual enforcement failed. These failures are retained and are not green evidence.

AOSP MediaSessionStack selects the addressed media session from audio playback UID history. The controlled FakeEngine previously changed StateFlow values without producing any platform audio activity. The test now creates a real looping AudioTrack with zero-valued PCM, checks STARTED playback state, and waits until dumpsys identifies this production route's own MediaHub.Foreground session as the addressed target. It then sends the same real shell key events. No production callback or engine method is invoked to simulate system delivery.

All original Pause, repeated Pause, Play, repeated Play, single Toggle, stopped-without-another-Compose-frame, frozen position and disposal assertions remain. The auxiliary output is released in After even on failure. No audible waveform, audio hardware quality or native decoded-media acceptance is claimed. Only parsed ownership/state flags are logged, not raw system metadata.

## Evidence boundary

Final instrumentation APK compile exit0,193 tasks (6 executed),2m48s. The preceding prepared-file read failed under GBK before making any source edit; its associated build verified the unchanged old test and the cold-UA app only. It is not counted as fixture validation. UTF8 preparation was corrected and the actual final fixture compiled separately. All logs remain private.

Runtime remains CI_PENDING until the new candidate's API32/API36 reports are inspected. A successful compile is not a routed-key pass, and this diagnosis remains unproven until actual execution. Mandatory product assertions and gates were not removed. Phone UI/install remains deferred at the human's request. G7 DO_NOT_MERGE.

Primary routing reference: [AOSP MediaSessionStack.updateMediaButtonSessionIfNeeded](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/media/MediaSessionStack.java).
