# Safe manual sync utility content port

## Candidate and scope

Feature `codex/port-safe-sync-utility-20261025`, parent
`a5aff482bf0bf27fd7318b8cfc8bcbb7b99fdf20` (Draft #65).
Content-port the useful checkpoint/window behavior from legacy maintenance tip
`937c1cadff9cbabe73a6bcf1ea0858f5aacffa31` without importing its history or installing a schedule.
Android, Windows player and user data code are unchanged.

## Reproduction and correction

Original script executed against a disposable local Git repository and local bare remote:
one test, one actual assertion failure, exit 1. A pre-staged unrelated asset was included
in the README commit. Original transcript is retained privately outside the repository.
No production repository, GitHub remote, media account or device was used by this test.

The port defaults to a read-only preview. Explicit Apply refuses existing staged/tracked
work, checks README is tracked and uses a README-only commit. It creates a new feature
branch before changing README when starting on main/master. Preview does not fetch,
write logs/checkpoints/locks or refresh the index. Worktrees use their actual Git
directory. A held exclusive file handle replaces timestamp-based lock eviction.

Successful checks retain the rolling checkpoint and first-run 24-hour window, including
work across midnight. Future/corrupt checkpoints fall back to the initial window.
Generated sync commits are excluded to prevent self-generated updates. README contains
dates/IDs; raw commit subjects are not copied into README or generated PR descriptions.
The optional GitHub path creates Draft PRs only. Push/PR failures leave the checkpoint
unchanged so publication can be retried.

## Executed validation

`python -X utf8 -m unittest discover -s scripts/tests -p test_github_sync_safety.py -v`

- Final run: **13 tests, 0 failures/errors/skips, exit 0**, 97.626 seconds.
- Real local Git commits/pushes: README-only update, untracked asset retention, preserved
  main ref, worktree `.git` file, unchanged preview bytes/refs/index/remote, repeat-run
  idempotence, previous-day checkpoint, future checkpoint, held live lock, staged and
  dirty tracked content, untracked README and private-subject sentinel.
- PowerShell AST parse: exit 0. Source is UTF-8 with BOM.
- Initial GREEN attempt: 11 tests, 1 assertion failure caused by a fixture that counted
  its recent initialization commit as well as the intended previous-day commit.
  Preserved this failure, backdated the fixture initialization, then reran all 13 tests.

The new `Sync utility safety` workflow runs the same disposable fixtures on Windows,
checks the literal checkout SHA and uploads its transcript. New-head CI is **CI_PENDING**
until a completed run is audited. GitHub PR creation itself is **NOT_RUN** by the local
fixtures; no real remote is exercised. No Gradle task is required for this script-only
increment, and no Android runtime/device PASS is inferred from these tests.

## Remaining integration boundary

G7 remains **DO_NOT_MERGE**. This content disposition applies only to the legacy manual
maintenance utility. Newer pairing/TLS/listener changes at remote cross-device tip
`25c56d450763826320381d77f3513ed473f8c563` still need independent reconciliation.
Phone acceptance remains deferred at the user's request; paused-surface recovery and
cold-start behavior have no new physical-device result from this increment.
