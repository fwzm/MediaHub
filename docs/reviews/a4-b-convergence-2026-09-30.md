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
| W1 | Exact-head SlowFinal CI failure; Call observer under investigation |
| W2 | Cache boolean ABA/global cleanup/release/SAF landing under investigation |
| W3 | Semantic dispatcher exists; both-order reachable interleavings and unknown count pending |
| W4 | filename+selected exists; primary sid/already loaded/asynchronous confirmation pending |
| W5 | main→head 265 changed files, module inventory to follow; historical fixes traced rather than reimplemented |
| W6 | Production player information/subtitle, settings info and backup navigation wired; runtime route tests pending |
| W7 | Backup absorbed PR18; schema4 subtitle memory present; SQL/Room/real-old-app tiers separate |
| W8 | Stop/retry/manual selection/late results and resource baseline evidence pending |
| W9 | Existing mandatory visual/parser gate needs provenance/negative fixture checks |
| W10 | Single B patch candidate to freeze; PR24 remains separate until A接回 |
| W11 | This ledger is recovery entry; final external evidence index/report pending |

## Entry map

| UI entry | State/use case | Provider/engine | Persistence/tests |
|---|---|---|---|
| Player controls→information; settings→professional mode | PlayerRoute/PlayerInfoPanelSheet; updateProfessionalInfo | PlaybackSource + engine UI state, unknown decoder retained | UserPreferencesStore; PlayerInfoPanelStateTest/preferences tests |
| Player controls→subtitle center/import/off/offset | PlayerViewModel subtitle discovery/apply/manual operations | ProviderHandle.subtitleDiscovery→local/WebDAV; SwitchablePlaybackEngine→Media3/mpv | SubtitleMemoryRepository; subtitle center/cache/native adapter tests |
| Add source→WebDAV browse/detail/play/back | AddServerViewModel, Library/Detail/Player ViewModels, nav codec | WebDav auth/browse/detail/playback/discovery/CallBridge | ServerStore/credentials; WebDavJointChainTest |
| Settings→sync and backup | BackupScreen/BackupViewModel/BackupRepository | SAF file store, frozen identity/restore journal | Room/DataStore/private snapshot; PR18 regressions and device process-death tests |

Status dimensions are independent: local_tests IN_PROGRESS; ci FAILURE at baseline; emulator API32/36 historical exact-baseline CI SUCCESS (new patch NOT_RUN); physical_device NOT_RUN; real_server NOT_RUN; old_database_upgrade NOT_RUN; backup_C C REVIEW PENDING / DEVICE UNVERIFIED; independent_review PENDING (B patches PENDING_INDEPENDENT_REVIEW).
