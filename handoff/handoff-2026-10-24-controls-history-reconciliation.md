# Controls history reconciliation - 2026-10-24

## State
PR46 original 5236f22 and replayed 1858d94 preserved by additive merge edf9602. Main unchanged. Current local control tests PASS; new exact-head CI remains required after push. Earlier force-push incident retained as historical fact.

## Goal
Complete all active branch integration evidence before claiming main readiness. The complete open-PR ancestry and remaining merge conflicts are recorded in docs/reviews/main-readiness-2026-10-24.md.

## Failed attempts
RED against current joint production: PlaybackEnginePlaybackControlTest 4 tests / 1 failure and MpvPlaybackEngineTest 26 tests / 1 failure, exit 1. Raw logs and XML retained outside the repo. No compile or environment failure is counted as product RED.

## Progress and next action
GREEN same 30 tests / 0 failures, exit 0, rerun-tasks/no-build-cache; Python gates 52/52 pass. Push additively to PR46 and obtain CI at its literal new head. PR53 production-component migration is on codex/main-readiness-seek-20261024 at 6522099; its baseline tests have started. Device serial 123e243f is offline. Do not close, mark ready, merge PRs or force push.
