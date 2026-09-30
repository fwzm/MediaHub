"""Bind unit XML to this run and require every configured module with test sources."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("evidence_gate", Path(__file__).with_name("verify-visual-tests.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def modules(root):
    active, no_source = {}, []
    settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    for name in re.findall(r'include\("(:[\w:]+)"\)', settings):
        module = name.lstrip(":").replace(":", "/")
        directory = root / module
        sources = [p for p in (directory / "src/test").rglob("*") if p.suffix in (".kt", ".java")]
        if not sources:
            no_source.append(module)
            continue
        build = (directory / "build.gradle.kts").read_text(encoding="utf-8")
        task = "testDebugUnitTest" if "libs.plugins.android." in build else "test"
        active[module] = f"build/test-results/{task}"
    return active, sorted(no_source)


def configure(root):
    active, no_source = modules(root)
    gate.MODULES = tuple(active)
    gate.REPORT_DIRECTORIES = active
    gate.MANIFEST = "ci-evidence/unit-manifest.json"
    gate.REJECT_SKIPS = True
    gate.required_tests = lambda api: set()
    return active, no_source


def record(root, sha, run_id, attempt):
    active, no_source = configure(root)
    gate.record(root, "unit", sha, run_id, attempt, "unit")
    path = root / gate.MANIFEST
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest.update({"modules": active, "noSourceModules": no_source})
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def verify(root, sha, run_id, attempt, required_suites=(), complete_suites=False):
    active, no_source = configure(root)
    problems = gate.verify(root, "unit", sha, run_id, attempt)
    try:
        manifest = json.loads((root / gate.MANIFEST).read_text(encoding="utf-8"))
        if manifest.get("modules") != active or manifest.get("noSourceModules") != no_source:
            problems.append("unit manifest: source module/NO-SOURCE inventory does not match checkout")
    except (OSError, ValueError):
        pass  # Common manifest verifier already records this failure.
    suites, totals, executed = set(), {}, set()
    for module, report_directory in active.items():
        cases = []
        for path in (root / module / report_directory).rglob("TEST-*.xml"):
            try:
                cases.extend(ET.parse(path).getroot().iter("testcase"))
            except (OSError, ET.ParseError):
                continue
        if not cases:
            problems.append(f"{module}: zero executed unit tests despite test sources")
        suites.update(c.get("classname", "") for c in cases)
        executed.update((c.get("classname", ""), c.get("name", "").removesuffix("()")) for c in cases)
        totals[module] = len({(c.get("classname"), c.get("name")) for c in cases})
    for suite in sorted(set(required_suites) - suites):
        problems.append(f"{suite}: required unit suite did not execute")
    if complete_suites:
        # These explicitly required, ordinary Kotlin/JUnit suites are source contracts.
        # Suite presence alone would allow --tests Suite.oneMethod to omit a regression.
        for suite in sorted(set(required_suites)):
            filename = suite.rsplit(".", 1)[-1] + ".kt"
            sources = []
            for module in active:
                for path in (root / module / "src/test").rglob(filename):
                    package = re.search(r'^\s*package\s+([\w.]+)', path.read_text(encoding="utf-8"), re.MULTILINE)
                    if package and package.group(1) + "." + filename[:-3] == suite:
                        sources.append(path)
            if len(sources) != 1:
                problems.append(f"{suite}: required Kotlin suite source missing or ambiguous")
                continue
            source = sources[0].read_text(encoding="utf-8")
            names = re.findall(r'@Test(?:\([^)]*\))?\s+(?:@[^\n]+\s+)*(?:public\s+)?fun\s+(`[^`]+`|\w+)\s*\(', source)
            if not names:
                problems.append(f"{suite}: no ordinary Kotlin @Test methods discovered")
            for name in names:
                name = name.strip("`")
                if (suite, name) not in executed:
                    problems.append(f"{suite}#{name}: required unit method did not execute")
    return problems, totals, no_source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--clean", action="store_true")
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--begin", action="store_true")
    parser.add_argument("--sha")
    parser.add_argument("--run-id")
    parser.add_argument("--attempt")
    parser.add_argument("--required-suite", action="append", default=[])
    parser.add_argument("--require-complete-suites", action="store_true")
    parser.add_argument("--report-timezone")
    args = parser.parse_args()
    if args.clean:
        # Only generated result directories of configured modules in the explicit checkout.
        active, no_source = modules(args.root)
        workspace = args.root.resolve()
        for module in list(active) + no_source:
            target = args.root / module / "build/test-results"
            if not target.resolve().is_relative_to(workspace):
                raise ValueError(f"generated report path escapes checkout: {target}")
            shutil.rmtree(target, ignore_errors=True)
        (args.root / "ci-evidence/unit-manifest.json").unlink(missing_ok=True)
        return 0
    if not all((args.sha, args.run_id, args.attempt)):
        parser.error("--sha, --run-id and --attempt are required for recording/verifying")
    if args.begin:
        configure(args.root)
        actual = gate.clean_checkout(args.root, args.sha)
        gate.begin(args.root, "unit", args.sha, args.run_id, args.attempt, actual, args.report_timezone)
        return 0
    if args.record:
        gate.clean_checkout(args.root, args.sha)
        record(args.root, args.sha, args.run_id, args.attempt)
        return 0
    problems, totals, no_source = verify(args.root, args.sha, args.run_id, args.attempt, args.required_suite, args.require_complete_suites)
    print(json.dumps({"uniqueTestsPerModule": totals, "uniqueTests": sum(totals.values()),
                      "noSourceModules": no_source}, indent=2))
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
