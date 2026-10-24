# Subtitle default font correction - 2026-10-25

## State

PR58 head175b31c14155bc404a0f2d75a4e1a9ac7d550f37 passed all three Android CI checks (40108687279). Downloaded XML:257 suites,2349 unique records,23 environment-gated skips,2326 executed,0 failures/errors. The23 skips do not count as executed passes. On physical device123e243f, installed APK7130c974a0827bf877da1c11ba26ec98c475fc9aac33bb20e83bc44e595e8a9a used the existing authorized Emby source for normal playback. Three subtitle tracks appeared; Simplified Chinese and English rendered; Off read back sid=no. AC3 input reported actual audio/raw output and the false warning disappeared. No real server details or raw dumps are committed.

## Additional finding and minimal fix

A synthetic caption containing known spaces exposed an OEM fontconfig fallback defect: libass selected an Android clock font with zero-width spaces. Private on-device font metrics confirmed space advance0 at48px for that selected clock font, versus11.90625 for the installed Roboto text font. Set the default mpv sub-font to Roboto; keep system CJK fallback. No font binary is added.

## Executed evidence and failed attempts

- Production-engine regression RED8 tests/1 assertion failure, exit1,57 tasks executed, forced rerun/no build cache; expected Roboto option was absent.
- Two GREEN attempts were interrupted by `Gradle build daemon has been stopped: stop command received`, exit1, before MPV tests. Preserve as environment interruptions, not failing product tests.
- Another checkout was actively building, so no competing Gradle was started. Later verified the installed Gradle8.14 DaemonBuildOptions.BaseDirOption supports org.gradle.daemon.registry.base. Use a private daemon registry while retaining the normal shared dependency cache and serial build flags; no other process was stopped.
- Final forced GREEN through that private registry:100 MPV tests/0 failures/errors/skips, exit0,57 tasks executed. These replace the previous99 in the scoped648 total; resulting scoped unique test coverage649, not748.
- A single600s cue fell outside normal bounded Matroska preroll after selection/seeking; this fixture cannot establish a switching regression. Replaced it with periodic2s cues. At head175, English appeared at the current expected cue immediately after the normal selection/dismiss flow. No speculative seek/cache change was added.

## Next action

Build and verify the new font candidate, compare package/version/signer, install-r without clearing data, then check readable word spaces, CJK rendering, subtitle selection/Off and PCM output again. The prior candidate's device/CI evidence must not be called final-head evidence. New candidate CI is pending until pushed and completed. Keep PR58 Draft; main and all PR merge states unchanged; G7 DO_NOT_MERGE.
