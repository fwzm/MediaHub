# Foreground media keys - 2026-10-25

The foreground player now owns an Android platform MediaSession for both playback backends. System Play and Pause use absolute commands; Toggle reads authoritative engine intent. Session ownership stops synchronously on ON_STOP and releases on disposal/ON_DESTROY, independent of whether a stopped Compose window renders another frame.

## Executed evidence

- RED: controller/native control targeted13 tests /5 assertion failures; fallback/loading43 tests /5 assertion failures; both exit1 with0 errors/skips. On physical c18 baseline, explicit Pause left burned video clock advancing.
- First complete regression365 tests /5 failures (extra initialization pause write); preserved and corrected by keeping native startup default when there is no explicit intent. Corrected mpv103/0 forced57 tasks exit0. Final affected modules: engine144 +mpv103 +feature/player118 =365 tests,0 failures/errors/skips. Every contributing module execution used --rerun-tasks --no-build-cache.
- Latest feature/player lifecycle run: exit0,155 tasks executed,2m23s. A first JVM native metaspace allocation failure is retained as BLOCKED_ENV. A later APK build native allocation failure is also retained. Final recovered app and instrumentation APK build: exit0,514 tasks (7 executed),59s. All Gradle invocations used JDK21, no daemon, no parallelism, one worker, in-process Kotlin; recovered heap1536m/two active processors.
- Gate scripts59/0. An initial new selftest stamping the wrong API identity is preserved and corrected.
- Physical device123e243f, same application/version/signature install-r and pulled installed hash verified. APK SHA256:9011c040c535d6cf12cc9b39e61908c6240af09abc08f28971e1f9e301c36581. Fifteen source input hashes recorded privately and rechecked before commit.
- Fresh controlled600s item qa-periodic-r6.mkv, seek about183s. Pause and repeated Pause produced identical full screenshot pixels over5s (frame76); Play advanced frame130 to221; Toggle froze frame308. Chinese cues remained rendered. Own session: foreground active=true; HOME active=false; return active=true and PAUSED; route exit removed the session. First background test active=true failure is retained; synchronous lifecycle observer fixes it.
- New system-key instrumentation covers actual shell key dispatch, idempotence, no callback when stopped without advancing the Compose frame clock, and disposal. It compiles locally and is mandatory on both emulator API levels. New-head CI runtime remains CI_PENDING until inspected.

## Open evidence boundary

Returning while paused produced a black screenshot after2s although the session remained PAUSED at193197ms. Paused video surface restoration is PARTIAL/observed failure and requires its own bounded diagnosis and correction. Do not promote the session-state pass to a visual return pass. No physical Bluetooth accessory, real Emby reacceptance or Media3 physical pass is claimed by this controlled mpv round.

Parent c18f6a61 has all five CI checks successful: Android run40125680120, Windows40125680119; checkout/head provenance c18 (event merge7bec has identical tree). Unit2406 unique =2383 pass +23 gated peer skips; Windows peer23 exact identities all pass; API32/API36 each90 pass +1 expected capability skip, including non-overwriting backup UI. Those parent results do not validate this new source.

Raw dumps, screenshots, crash logs, credentials and server details remain outside Git. G7 DO_NOT_MERGE. Cold URL-index readiness, later sync matrix delta and legacy content dispositions remain open.
