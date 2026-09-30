"""Fail CI if mandatory visual assertions were missing, skipped, or failed."""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


MODULES = ("core/ui", "feature/player", "feature/settings")
COMMON_TESTS = {
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


def verify(root, api):
    passed = set()
    problems = []
    for module in MODULES:
        reports = sorted(
            (root / module / "build/outputs/androidTest-results/connected").rglob("TEST-*.xml")
        )
        if not reports:
            problems.append(f"{module}: no connected-test XML reports")
        for report in reports:
            try:
                suite = ET.parse(report).getroot()
            except (ET.ParseError, OSError) as error:
                problems.append(f"{report}: invalid report ({error})")
                continue
            for summary in suite.iter("testsuite"):
                try:
                    if any(int(summary.get(field, "0")) for field in ("failures", "errors")):
                        problems.append(f"{report}: suite reports failures or errors")
                except ValueError:
                    problems.append(f"{report}: invalid failure/error counts")
            for case in suite.iter("testcase"):
                key = (case.get("classname", ""), case.get("name", ""))
                label = ".".join(key)
                if case.find("failure") is not None or case.find("error") is not None:
                    problems.append(f"{label}: failed")
                elif case.find("skipped") is not None:
                    if key in required_tests(api):
                        problems.append(f"{label}: mandatory assertion was skipped")
                else:
                    passed.add(key)
    for key in sorted(required_tests(api) - passed):
        problems.append(f"{'.'.join(key)}: no passing execution on API {api}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", required=True, type=int, choices=(32, 36))
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    problems = verify(args.root, args.api)
    if problems:
        print("Visual instrumentation gate failed:", file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        return 1
    print(f"API {args.api}: all {len(required_tests(args.api))} mandatory visual tests executed and passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
