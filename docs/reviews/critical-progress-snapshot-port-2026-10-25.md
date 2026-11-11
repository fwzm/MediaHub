# Critical progress snapshot content port

## Candidate and reproduced contract gap

Feature `codex/port-critical-progress-snapshots-20261025`, parent
`53ac0bdb3b6f0058a4e5a5662957eb2f0608ba17` (Draft #68). Legacy architecture tip
`47eca35054acb3718282344786ef341b990462f0` carried progress with each critical event;
the current implementation queued only a kind and flushed the last periodic sample.

Actual RED used the existing Media3 engine and coordinator with its controlled
native-player proxy. Between periodic ticks, enqueue Seek20s, Pause and Seek40s,
then consume the queue. Expected local/remote positions: `[20000,20000,40000]`;
actual local positions: `[0,0,0]`. One AssertionError, exit1, 80 forced tasks/4m27s.
No new API or behavior seam was needed to make that original reproduction run.

## Correction and retained boundaries

PlaybackEvent keeps its five enum values and implements PlaybackEventMessage.
Production controls carry an immutable PlaybackEventSnapshot with the original
owner and progress. Legacy enum producers remain accepted through Flow covariance;
consumers compare `message.kind`. This is an in-repository typed-flow API change,
not a promise of compatibility with externally compiled engine consumers.

- Media3 captures owned progress before native/state callbacks can reenter; old
  controls cannot overwrite a replacement session's playing intent.
- mpv reads native progress under nativeLock before taking stateLock. Its committed
  seek records the normalized requested target because native seek is asynchronous;
  later periodic samples provide decoded position. END_FILE captures the last owned
  UI progress before publishing state, without reading JNI under stateLock.
- The switchable facade forwards the same envelope object, without resampling.
- The coordinator uses the captured progress for Pause/Seek/Ended. Existing sampling
  intervals, preview-seek suppression and Resumed behavior are unchanged. Stopped
  remains a notification: explicit final flush is still the sole exit authority.
  The legacy ProgressSyncGate's reset-on-critical sampling-window policy is not
  adopted: current independent sample clocks remain authoritative under ADR-017.
- The ViewModel subscribes the multicast bus before starting the single engine tap.
  An ended snapshot only advances autoplay when its server/item matches the current
  owner; legacy enum-only events retain their compatibility path.
- Snapshot toString prints the kind only, avoiding automatic metadata/URL logging.

## Validation record

Initial forced engine/mpv/feature-player run: **400 tests, zero failures/errors/skips,
exit0**, 184 tasks, 12m20s. Four additional edge tests then exposed three real
intermediate-candidate regressions: Media3's old EOF received the new item's identity,
mpv's EOF received position0 instead of the old15s position, and a stale ended frame
advanced the current episode. The initial-subscriber case already passed. The edge
run executed eight tasks/176 up-to-date, exit1; all original XML/logs are retained
privately. These regressions were found and corrected before publication.

Final forced three-module regression: **405 passed, zero failures/errors/skips,
exit0**, 184 tasks executed, 12m18s (engine166, mpv111, feature-player128). It includes the original queued
control reproduction, facade identity forwarding, native pause/seek snapshot values,
EOF reentry ownership, pause reentry intent, initial subscriber delivery, stale EOF
suppression and existing legacy/final-flush behavior. Changed inputs are hashed and
must remain identical during execution. Added mandatory suite names preserve all
previous CI requirements and enforce complete methods for the facade/coordinator.

App/test APK and engine/mpv/feature-player lint: **exit0**, 732 tasks (92 executed,
640 up-to-date), 10m52s. Built the app, app test APK and feature-player test APK.
App APK SHA-256:
`d75aeb0e15dede71d3fd15ea9ed9ae2bb613e5e671e4520058c1098c48d09ae0`.
New-head full CI: **CI_PENDING**. No physical-device pass is claimed; the human phone
deferral remains active. Source, unit, instrumentation, build/lint and physical
evidence are separate.

## Remaining integration work

This ports a useful progress contract while retaining current provider IDs, encrypted
storage and explicit final-flush architecture. It does not complete the entire119-path
legacy review. Registry duplicate-key fail-fast and ProviderHandle declared/runtime
capability validation need proof/disposition; WebDAV's legacy charset negotiation
also differs from the current UTF8 policy and remains unresolved. Do not mark those
contracts covered from green player tests. G7 remains **DO_NOT_MERGE**.
