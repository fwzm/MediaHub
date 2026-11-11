# Lifecycle fixture correction - 2026-10-24

## State
PR57 previous head5e028a31 Android build failed one of eight lifecycle cases; Windows/API32/API36 passed. The added body-read precondition was incompatible with the zero-body fixture. Current test-only fix sends one byte then withholds the remaining499, verifies real client receipt, and retains cancellation/peer-close checks.

## Goal
Finish verified integration readiness for every valid active branch; PR6 reconciliation and physical-device limits remain open.

## Failed attempts
CI40096618857 XML retained locally: body-read precondition failure,8/1. Local combined session79596/PID48872 disappeared without an exit record; source5e028a31 log ended in compile. Archived INTERRUPTED_ENV, not PASS.

## Progress and next action
Corrected lifecycle8/0 PASS exit0,27 executed tasks,forced rerun/no-cache. Commit/push test correction and obtain exact-head full CI. PR6 tests prepared on separate clean-derived codex/pr6-main-readiness-20261024 in D:/deepseek_test/mh-g4-obs; no parallel Gradle. Search RED harness retains old empty-flow behavior with constructor/getter plumbing solely to compile the new saved-state contract. Do not count this plumbing as a restoration fix.
