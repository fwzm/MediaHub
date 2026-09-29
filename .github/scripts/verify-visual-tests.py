"""Require fresh, revision/API-bound mandatory visual and parser assertions."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import subprocess
import time
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


MODULES = ("core/ui", "feature/player", "feature/settings", "provider/webdav", "core/database")
COMMON_TESTS = {
    (
        "com.mediahub.feature.player.PlayerRouteVisualEffectsTest",
        "productionPlayerInformationAndSubtitleActionsPreservePlaybackSession",
    ),
    (
        "com.mediahub.core.database.RoomMigrationDeviceTest",
        "historicalSchemasMigrateAndReopenThroughActualRoom",
    ),
    (
        "com.mediahub.feature.settings.VisualEffectsSettingsEntryTest",
        "productionSettingsInfoDensityPersistsAndBackupEntryDispatches",
    ),
    (
        "com.mediahub.feature.player.PlayerVisualEffectsPathTest",
        "playerEntryOpensSettingsAndPresetOffPathUpdatesProductState",
    ),
    (
        "com.mediahub.feature.player.PlayerRouteVisualEffectsTest",
        "productionPlayerRoutePersistsAllPresetsIntensityAndOffAcrossReentry",
    ),
    (
        "com.mediahub.feature.settings.VisualEffectsSettingsEntryTest",
        "realSettingsRoutePersistsAndReloadsVisualPreferencesThenRestoresDefaults",
    ),
    (
        "com.mediahub.core.ui.effects.FlowGlowCompositionClockTest",
        "disabledHiddenStoppedAndDisposedCompositionsOwnNoFrameLoop",
    ),
    (
        "com.mediahub.core.ui.effects.FlowGlowRuntimeShaderTest",
        "forcedFallbackProducesPixelsWithoutSamplingAudio",
    ),
    (
        "com.mediahub.core.ui.effects.PlayerVisualThemeCompositionTest",
        "paletteTransitionUpdatesComposedThemeSurfaceAndSliderWithoutLeakingOutsidePlayer",
    ),
    (
        "com.mediahub.core.ui.effects.PlayerVisualChromeBoundsTest",
        "tallLandscapeControlsKeepAmbientOutsideSubtitleSafeBand",
    ),
    # A4：Android runtime 生产 parser 双验收门禁（缺失/跳过=失败，
    # 防回归"job success 但 webdav 测试缺失/被跳过"）
    (
        "com.mediahub.provider.webdav.WebDavMultistatusParserDeviceTest",
        "standard207WithI18nEncodingAndMixedPropstatOrdersParses",
    ),
    (
        "com.mediahub.provider.webdav.WebDavMultistatusParserDeviceTest",
        "externalEntityCausesZeroNetworkHops",
    ),
    (
        "com.mediahub.provider.webdav.WebDavMultistatusParserDeviceTest",
        "doctypeDeclarationIsRejected",
    ),
    (
        "com.mediahub.provider.webdav.WebDavMultistatusParserDeviceTest",
        "billionLaughsInternalEntitiesRejected",
    ),
    (
        "com.mediahub.provider.webdav.WebDavMultistatusParserDeviceTest",
        "failedPropstatCollectionDoesNotPollute",
    ),
    (
        "com.mediahub.provider.webdav.WebDavMultistatusParserDeviceTest",
        "responseLevel404DiscardsWholeEntry",
    ),
    (
        "com.mediahub.provider.webdav.WebDavMultistatusParserDeviceTest",
        "malformedStatusLineTreatedAsFailure",
    ),
    (
        "com.mediahub.provider.webdav.WebDavMultistatusParserDeviceTest",
        "platformSaxFeatureCapabilityDeterminesParserPath",
    ),
}


def required_tests(api):
    renderer_test = (
        "runtimeShaderCompilesAcceptsEveryUniformAndProducesPixels"
        if api >= 33
        else "pre33FallbackProducesPixelsWithoutRuntimeShader"
    )
    return COMMON_TESTS | {
        ("com.mediahub.core.ui.effects.FlowGlowRuntimeShaderTest", renderer_test)
    }


MANIFEST = "ci-evidence/instrumentation-manifest.json"
REPORT_DIRECTORIES = {module: "build/outputs/androidTest-results/connected" for module in MODULES}
REJECT_SKIPS = False


def start_path(root):
    return root / MANIFEST.replace("manifest.json", "execution-start.json")


def begin(root, api, sha, run_id, attempt, checkout_sha):
    if checkout_sha != sha:
        raise ValueError("execution start: actual Git checkout differs from expected SHA")
    if report_paths(root):
        raise ValueError("execution start: report roots must be empty before running tests")
    output = start_path(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"checkoutSha": sha, "api": api, "runId": run_id,
                                 "runAttempt": attempt, "startedNs": time.time_ns()}) + "\n", encoding="utf-8")


def execution_start(root, api, sha, run_id, attempt):
    ledger = json.loads(start_path(root).read_text(encoding="utf-8"))
    for field, value in {"checkoutSha": sha, "api": api, "runId": run_id, "runAttempt": attempt}.items():
        if ledger.get(field) != value:
            raise ValueError(f"execution start: {field} differs from requested identity")
    started = ledger.get("startedNs")
    if not isinstance(started, int) or started <= 0:
        raise ValueError("execution start: invalid start time")
    return started


def fresh_report(path, started):
    # NTFS/ZIP-backed fixture files may round last-write time to a few milliseconds.
    if path.stat().st_mtime_ns + 5_000_000 < started:
        raise ValueError(f"{path}: XML predates the recorded execution start")
    # Android/Gradle XML may omit timestamp. When present, reject a re-copied older report too.
    try:
        suites = ET.parse(path).getroot().iter("testsuite")
    except ET.ParseError:
        return  # The verifier will report malformed XML; keep failure evidence hash-bound.
    for suite in suites:
        stamp = suite.get("timestamp")
        if stamp:
            parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)  # CI runners execute in UTC.
            if parsed.timestamp() < started / 1_000_000_000 - 5:
                raise ValueError(f"{path}: suite timestamp predates the recorded execution start")


def report_paths(root):
    return sorted(path for module in MODULES for path in
                  (root / module / REPORT_DIRECTORIES[module]).rglob("TEST-*.xml"))


def record(root, api, sha, run_id, attempt, device_api):
    if device_api != api:
        raise ValueError(f"device API {device_api} differs from matrix API {api}")
    started = execution_start(root, api, sha, run_id, attempt)
    for path in report_paths(root):
        fresh_report(path, started)
    manifest = {
        "checkoutSha": sha, "api": api, "deviceApi": device_api,
        "runId": run_id, "runAttempt": attempt,
        "startedNs": started,
        "reports": {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in report_paths(root)},
    }
    output = root / MANIFEST
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def verify(root, api, sha, run_id, attempt):
    passed = set()
    started = 0
    executions = Counter()
    problems = []
    reports_now = {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                   for path in report_paths(root)}
    try:
        started = execution_start(root, api, sha, run_id, attempt)
        manifest = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
        expected = {"checkoutSha": sha, "api": api, "deviceApi": api,
                    "runId": run_id, "runAttempt": attempt, "startedNs": started}
        for field, value in expected.items():
            if manifest.get(field) != value:
                problems.append(f"instrumentation manifest: {field} does not match expected {value}")
        if manifest.get("reports") != reports_now:
            problems.append("instrumentation manifest: report paths/content do not match recorded execution")
    except (OSError, ValueError, AttributeError) as error:
        problems.append(f"missing/invalid instrumentation manifest ({error})")
    for module in MODULES:
        reports = sorted(
            (root / module / REPORT_DIRECTORIES[module]).rglob("TEST-*.xml")
        )
        if not reports:
            problems.append(f"{module}: no connected-test XML reports")
        for report in reports:
            try:
                fresh_report(report, started)
                suite = ET.parse(report).getroot()
            except (ET.ParseError, OSError, ValueError, UnboundLocalError) as error:
                problems.append(f"{report}: invalid report ({error})")
                continue
            for summary in suite.iter("testsuite"):
                cases = list(summary.findall("testcase"))
                try:
                    if any(int(summary.get(field, "0")) for field in ("failures", "errors")):
                        problems.append(f"{report}: suite reports failures or errors")
                    if int(summary.get("tests", "-1")) != len(cases):
                        problems.append(f"{report}: suite test count does not match testcase records")
                    for field, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                        if int(summary.get(field, "0")) != sum(c.find(tag) is not None for c in cases):
                            problems.append(f"{report}: suite {field} count does not match testcase records")
                except ValueError:
                    problems.append(f"{report}: invalid suite counts")
            for case in suite.iter("testcase"):
                key = (case.get("classname", ""), case.get("name", ""))
                if not all(key):
                    problems.append(f"{report}: testcase lacks stable class/name identity")
                executions[key] += 1
                label = ".".join(key)
                if case.find("failure") is not None or case.find("error") is not None:
                    problems.append(f"{label}: failed")
                elif case.find("skipped") is not None:
                    if REJECT_SKIPS or key in required_tests(api):
                        problems.append(f"{label}: mandatory assertion was skipped")
                else:
                    passed.add(key)
    for key, count in sorted(executions.items()):
        if count > 1:
            problems.append(f"{'.'.join(key)}: duplicate execution ({count}); use separate API/run evidence roots")
    for key in sorted(required_tests(api) - passed):
        problems.append(f"{'.'.join(key)}: no passing execution on API {api}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", required=True, type=int, choices=(32, 36))
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--attempt", required=True)
    parser.add_argument("--record", action="store_true", help="Record report hashes after fresh instrumentation")
    parser.add_argument("--begin", action="store_true", help="Require clean reports and bind execution start to Git HEAD")
    parser.add_argument("--device-api", type=int)
    args = parser.parse_args()
    if args.begin:
        actual = subprocess.check_output(["git", "-C", str(args.root), "rev-parse", "HEAD"], text=True).strip()
        begin(args.root, args.api, args.sha, args.run_id, args.attempt, actual)
        return 0
    if args.record:
        record(args.root, args.api, args.sha, args.run_id, args.attempt, args.device_api)
        return 0
    problems = verify(args.root, args.api, args.sha, args.run_id, args.attempt)
    if problems:
        print("Instrumentation evidence gate failed:", file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        return 1
    print(f"API {args.api}: {len(required_tests(args.api))} unique mandatory visual/parser tests passed at {args.sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
