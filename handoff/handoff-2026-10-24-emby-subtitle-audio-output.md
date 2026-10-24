# Emby subtitles and audio output evidence - 2026-10-24

## State

Feature codex/fix-emby-track-evidence-20261024 starts at39c4b5a7f3f7b09f182dc01a116b150a37289839 (PR6). The user reported missing Emby subtitles and a persistent AC3 no-output warning. MPV published no subtitle list and ignored non-null subtitle selection; Emby playback discarded server sidecars. The UI incorrectly classified a null audio format as failed output.

## Goal and implementation

Pass only the selected media version's supported text sidecars through ephemeral PlaybackSource metadata to the subtitle center. Use the official same-server subtitle endpoint; leave credentials in existing protected session headers. Never copy DeliveryUrl credentials or arbitrary origins. Existing origin, cache and session-generation protections remain in use. SRT/ASS/SSA/VTT sidecars are supported; external bitmap subtitle loading is outside this patch.

MPV now publishes subtitle rows, translates typed UI indices to native sid, and reads actual selection. Refresh after FILE_LOADED/AUDIO_RECONFIG and through the existing progress loop, including events preceding resource publication. Read audio-out-params/format rather than inferring failure from encoded AC3/AAC. UNKNOWN is distinct from ACTIVE/UNAVAILABLE. Media3 records audio position advancement and fatal AudioTrack errors; an input codec or missing format alone does not establish output failure.

## Executed evidence and retained failures

All Gradle calls use JDK21, no daemon, no parallelism, max-workers1, Xmx2048m and in-process Kotlin. Required unit runs include --rerun-tasks --no-build-cache.

- RED production MPV engine:7 tests,2 assertion failures, exit1,57 tasks executed. The failures demonstrate empty subtitle publication and missing PCM output evidence.
- RED provider/VM/warning chain:54 tests,5 assertion failures, exit1,213 tasks executed. No compile failure counted as behavioral RED.
- GREEN player:engine/player:mpv/provider:emby/feature:player/core:model/provider:api:648 tests,80 suites,0 failures/errors/skips, exit0,232 tasks executed.
- Final forced MPV rerun after linear subtitle-row lookup refinement:99 tests,0 failures/errors/skips, exit0,57 tasks executed. These99 overlap the648 and are not additional unique tests.

Raw logs, XML and synthetic-device fixtures remain outside the repository under the local main-readiness evidence directory. No real server address, account, token or raw UI dump belongs in Git/PR.

## Next action and gates

Build the candidate APK, verify applicationId/versionCode/signer against the installed package, and use same-signer adb install -r only. Verify normal playback with user-authorized already-added Emby sources and the synthetic AC3/AAC/two-subtitle fixture. No server scan/load test, no uninstall/data clear/database overwrite, and no rotation-setting changes. At this commit checkpoint physical acceptance and exact-head CI are NOT_RUN/CI_PENDING; report subsequent evidence separately.

Preserve original main-readiness work: latest fetch also observed Windows and cross-device branch advances, not yet integrated. Main unchanged; keep PRs Draft and G7 DO_NOT_MERGE.
