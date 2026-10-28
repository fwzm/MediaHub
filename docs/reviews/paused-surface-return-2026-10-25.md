# mpv surface ownership - 2026-10-25

The pinned libmpv JNI attach/detach functions only update wid and retain/delete a global Surface reference. The engine previously left its GPU video output bound to the old window. A fresh controlled600s item at20966d05 / APK9011c040 froze at frame54 before HOME; returning at2s and10s stayed black, and explicit Play3s later also stayed black. File timestamps11:57:49-11:58:07 precede the recorded external force-stop at12:01:31, so this baseline observation is not the later interrupted GREEN attempt.

A per-resource MpvSurfaceBinding now disables video output and force-window before detaching an owned JNI reference. It attaches the new window, forces window rendering and rebuilds the configured gpu output. Duplicate references and repeated null callbacks are idempotent; initially missing windows disable video without releasing an unowned reference. It sends no Play, Pause, Seek or Load command. Initialization/publication reconciliation and runtime surface callbacks use the same binding under the existing native lock.

## Evidence

- Baseline extraction retained the old native attach/detach behavior. Four new handoff tests executed and failed with assertions:4 tests /4 failures /0 errors/skips, exit1, forced57 tasks2m18s. Raw XML/log retained privately.
- Full corrected mpv module:108 tests /0 failures/errors/skips, exit0 with --rerun-tasks --no-build-cache. Includes a fifth initial-headless ownership case plus all prior subtitle/audio/control/lifecycle tests.
- App build exit0 in1m16s,483 tasks (6 executed). JDK21, no daemon/no parallel, one worker,1536m heap/two active processors, in-process Kotlin. Native dependency unchanged.
- Same application/version/signature install-r and pulled installed hash verified for device123e243f. Candidate APK SHA256:bc0b42a921e46eb7e1e4c9748e53e1f19c0d48576eac629d75278167eba708ef. Four changed input files are hashed privately and rechecked before commit.
- Initial controlled r8 playback rendered frame399 and Chinese cues on this candidate. Full GREEN return attempt is INVALID_UI_CONTEXT: at12:06:55 Android records USER REQUESTED/FORCE STOP, and first pre-HOME sample at12:07:10 was a different server screen. Crash buffer contains no corresponding app/native crash. No return PASS or product FAIL is inferred from that run. Human confirmed other device operations and requested testing later. Further device UI actions stopped.
- CI mandatory unit suite pins cover the new surface binding and the parent foreground media-session controller. New-head CI remains CI_PENDING until inspected.

## Limits and next gate

Device acceptance is PARTIAL/blocked by concurrent use, not complete. Reaccept on a fresh controlled item when the human says the device is available: record stable process identity, paused frame before HOME, restored visible frame after2s/10s with unchanged pause/position, then explicit Play advancing; also check a playing surface replacement. Preserve initial invalid captures. Do not simulate picture recovery by resuming or seeking. G7 DO_NOT_MERGE; no main/PR merge, close, ready or force push.

Primary implementation references: [pinned JNI render.cpp](https://github.com/jarnedemeulemeester/libmpv-android/blob/v1.0.0/libmpv/src/main/cpp/render.cpp) and [upstream Android surface lifecycle](https://github.com/mpv-android/mpv-android/blob/master/app/src/main/java/is/xyz/mpv/BaseMPVView.kt). These establish the handoff mechanism; local and physical evidence establish only the scopes stated above. Raw server/account/UI/crash artifacts remain outside Git.
