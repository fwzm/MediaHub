# Main readiness union - 2026-10-25

## State

In the owned D:/deepseek_test/mh-g4-obs worktree, branch codex/main-readiness-closure-20261025 starts at validated PR58 head3c51ce52. An additive merge of frozen sync b71edc2b is in progress; that parent contains Windows0ec00892. Only CI required-suite conflict needed resolution, preserving the union. No main or original feature branch rewritten.

## Goal

Create one reviewable main-facing Draft with current valid code, then continue remaining branch/device audit until actual main readiness is demonstrated. Do not call the original goal complete from this increment's narrower passes. See docs/reviews/main-readiness-closure-2026-10-25.md for frozen inputs and explicit remaining work.

## Executed and failed attempts

Windows solution build exit0/0 warnings/errors;1577 main and104 WebDAV tests executed, all pass/no skip. Native headless playback/seek/pause probe PASS; no GUI/GPU/speaker claim. Affected Android1145/0/no skips, exit0,262 executed tasks, forced/no-cache;23 actual CLI/Kotlin interop cases and fixed-seed16-round auth case included. Sync AndroidTest APK compiled, runtime pending new CI. Python gates56 PASS; first fixture run had5 API-identity errors, retained and fixed; omission mutation produced10 expected assertion failures/0 errors. Initial Android launch was blocked before Gradle by another checkout's active build; no competing process stopped or restarted.

## Next action

The separate core:network constructor-boundary run completed:226 tests/0 failures/errors/skips, exit0,27 executed tasks, forced/no-cache; no call factory reached for malformed input. Record source inputs, stage only intended files, commit the additive union, push this feature branch and create/attach a Draft PR targeting main. Observe exact-head Android and Windows jobs, including peer interoperability, without mixing the previous PR58 results. Continue the remaining safe backup UI port, media-key/runtime and legacy-branch dispositions. G7 DO_NOT_MERGE; preserve raw failures, private evidence and all unrelated worktrees/data.
