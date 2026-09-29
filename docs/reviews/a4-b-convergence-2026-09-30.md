# A4 Agent B convergence checkpoint (2026-09-30)

Baseline: PR #24 literal head `2f9032732fc8365d19b5badac90f66b10310f500`, main/base `8e516e40568e7d2eb309a1853f14a9c6c4ddc0b1`.
Live refresh: PR OPEN/Draft; no reviews or inline comments; CI `36197543412` FAILURE (build/unit failure, API32/36 SUCCESS). Body/handoff cite older `67af945` and are stale. No repository AGENTS.md found. Dirty primary checkout preserved.

## Ownership and resume

- Root: `codex/a4-b-convergence-20260930`, `D:/deepseek_test/mh-b-review/a4-2f90327`: feature/player, UI routes, integration/build and final evidence.
- Network worker: `codex/a4-b-network-20260930`, separate a4-b-network worktree: SlowFinal, WebDAV joint chain, network/provider-webdav.
- Subtitle worker: `codex/a4-b-subtitle-20260930`, separate a4-b-subtitle worktree: player/mpv + player/engine.
- Gate/backup worker: `codex/a4-b-gates-20260930`, separate a4-b-gates worktree: verification scripts, Room, backup/security/logging/provider auth.
- Evidence root: `D:/deepseek_test/mh-b-review/a4-b-evidence-20260930`; raw reports remain external to avoid self-referencing validation commits.
- Single Gradle/device slot controlled by root. No writes to A branches, no approval/merge or unattended jobs. B-authored changes require independent review.

## Work-package ledger

| Package | Baseline status / evidence to produce |
|---|---|
| W0 | Live baseline established; existing task/handoff/decisions read; ownership recorded |
| W1 | Call-specific observer reproduces SlowFinal failure; fixed assertions require target cancellation/terminal/dispatcher idle, not unrelated warmup callbacks. Five stability runs preserved separately. |
| W2 | Baseline-compatible HTTP/SAF cache barriers reproduced ownership, cleanup and release failures; per-run namespace/token and atomic commit fixed them. |
| W3 | Production CallBridge/WebDAV worker admission, mixed raw/%XX href and vaulted restart identity red/green regressions; semantic joint dispatcher tests cover both reachable orders and check unknown requests after shutdown. |
| W4 | mpv success requires full filename/type/id/selected plus actual primary sid, with bounded asynchronous confirmation. Actual Media3 selected Tracks must confirm external selection after Off/embedded overrides. |
| W5 | 265-file baseline delta inventoried; network/subtitle/backup incremental coverage retained externally. Root traced navigation, DI, preferences, source identity and player operations. Static inspection is separate from execution. |
| W6 | Actual SettingsRoute/PlayerRoute and MainActivity→WebDAV→browse→detail→Media3→discovery→SAF→reentry tests added. Device failures/results remain external; test presence is not device acceptance. |
| W7 | Private restore image captures all preferences and subtitle memory; selected replacement and ordinary live source identity changes invalidate only affected memory atomically. Real Room fixtures, synthetic process death, real old-app data and C review remain separate tiers. |
| W8 | Resolve/discovery/metadata/manual/Off ownership barriers, actual offset readback, accepted write drain, duplicate exit and shared-store recall serialization added. Bridge session sockets/Calls, capability path, bounded admission and monotonic header deadline covered. |
| W9 | Fresh clean literal-HEAD unit/visual manifests reject stale/missing/zero/skipped/duplicate/hash/API/run/timezone evidence. Mandatory suites include the new Room/source and player confirmation regressions. Negative fixtures use disposable reports/repositories. |
| W10 | One B branch incorporates ordered patches; freeze and exact-head validation/results are external to avoid self-referencing commits. PR24 remains separate until A接回. |
| W11 | This ledger is recovery entry. External evidence index owns final SHA, statuses, APK hashes, exact commands, findings, acceptance results and remaining tasks. |

## Entry map

| UI entry | State/use case | Provider/engine | Persistence/tests |
|---|---|---|---|
| Player controls→information; settings→professional mode | PlayerRoute/PlayerInfoPanelSheet; updateProfessionalInfo | PlaybackSource + engine UI state, unknown decoder retained | UserPreferencesStore; PlayerInfoPanelStateTest/preferences tests |
| Player controls→subtitle center/import/off/offset | PlayerViewModel subtitle discovery/apply/manual operations | ProviderHandle.subtitleDiscovery→local/WebDAV; SwitchablePlaybackEngine→Media3/mpv | SubtitleMemoryRepository; subtitle center/cache/native adapter tests |
| Add source→WebDAV browse/detail/play/back | AddServerViewModel, Library/Detail/Player ViewModels, nav codec | WebDav auth/browse/detail/playback/discovery/CallBridge | ServerStore/credentials; WebDavJointChainTest |
| Settings→sync and backup | BackupScreen/BackupViewModel/BackupRepository | SAF file store, frozen identity/restore journal | Room/DataStore/private snapshot; PR18 regressions and device process-death tests |

Status dimensions are independent: local_tests IN_PROGRESS; ci FAILURE at baseline; emulator API32/36 historical exact-baseline CI SUCCESS (new patch NOT_RUN); physical_device NOT_RUN; real_server NOT_RUN; old_database_upgrade NOT_RUN; backup_C C REVIEW PENDING / DEVICE UNVERIFIED; independent_review PENDING (B patches PENDING_INDEPENDENT_REVIEW).

## Findings and reproducible handoff

All changes on this B branch are **PATCH_READY / PENDING_INDEPENDENT_REVIEW**. Subagent review is internal. Baseline fixes already present (semantic WebDAV dispatch, cache extension whitelist, backup external whitelist, generation-aware home/auth and credential guards) were traced and retained rather than replaced wholesale.

| Finding | Old execution / root cause | Permanent regression / patch family | Remaining boundary |
|---|---|---|---|
| SlowFinal observer | Baseline CI unit failure; global Call terminal observation confuses warmup with final request | SlowFinalExitRegressionTest; call identity and both-order WebDavJointChainTest | Same psid/provider reuse needs an actual reachable reproducer |
| Cache lifecycle | Baseline mpv+engine probes: 92 executions, 15 failures | Mpv/PlaybackEngineExternalSubtitle HTTP/SAF landing barriers; captured run, unique cache ownership and scoped cleanup | Blocking OEM document IO interruption unverified |
| Native subtitle success | Filename/selected did not prove primary sid; Media3 queued config did not prove actual Tracks | MpvSubtitleTest and PlaybackEngineExternalSubtitleTest; primary/selected confirmation, Off and rollback | Physical video/cue rendering and OEM behavior unverified |
| Player operations | Resolve/manual/late discovery/Off/storage/offset regressions fail old behavior | PlayerViewModelSubtitleCenterTest; generation guards, shared memory mutex, NonCancellable exit, actual offset and SAF grant recall | Collector-emission ownership and multi-track group mapping need focused reproducer |
| WebDAV lifecycle | Worker permits released on cancellation before worker exit; mixed escaped href double encoded; restarted identity lost | Worker blocking barrier; mixed href; encrypted identity/password atomic restart cases | Real remote backend account collisions not executed |
| mpv loopback bridge | Baseline Java probe accepted arbitrary method/path and retained admitted socket | MpvHttpBridgeTest; per-session capability, socket/Call teardown, admission/header bounds, total deadline and dynamic hop header removal | Native production video playback through bridge not equated with host HTTP tests |
| Restore image/memory | New preferences omitted from private snapshot; same-id source replacement retained subtitle memory | RestoreSnapshotStoreTest, backup/Room identity tests; private codec v2, transactional invalidation/rollback | Legacy incomplete private journal blocks honestly; real old database upgrade/C review pending |
| Ordinary source edit/relogin | New actual Room suite: 11 cases, 8 old behavior failures | ServerRepositorySubtitleIdentityTest; server/endpoints/memory transaction, normalized active address + case-sensitive account identity | LOCAL has no standalone treeUri field; no invented SAF source model |
| Evidence provenance | Old XML could be relabeled; naive timestamps differed between Windows and UTC runner | Python negative fixtures and clean Git/timezone begin/record/verify gates | Exact final results belong to external frozen manifest |

Known unsuccessful attempts are retained externally: fixture OPTIONS mismatch, invalid screenshot name, missing test language compile failure, failed UTF-8 red setup and initial Media3 lifecycle proxy activation. Compile/preflight failures and stale copied XML are not counted as executed red tests. Corrected fixtures do not lower production assertions.

Agent A: verify PR24 still points to the stated baseline, then review and apply the B branch commit range in order in an isolated candidate. Use `git log --reverse --oneline 2f9032732fc8365d19b5badac90f66b10310f500..codex/a4-b-convergence-20260930`. Any changed head requires new manifests and full validation; this branch's green evidence does not certify PR24.

Independent reviewer/C: inspect the B-authored diff and retained red fixtures; run the unit/visual provenance negative tests, full configured unit tasks, lint and APK build on clean exact HEAD. Run API32/36 mandatory instrumentation. On a disposable emulator only, run `scripts/verify-backup-restore.ps1 -Serial <own-serial> -OutputDirectory <external-evidence> -IsolatedEmulator` after installing the matching app/test APKs. Nine intentional seed process deaths are expected evidence, not passing tests; require the actual UI flow and nine recovery/blocking checks. Separately schedule physical native/video, representative real-server, real old-app-data and backup C acceptance. Do not approve/merge on this handoff alone.
