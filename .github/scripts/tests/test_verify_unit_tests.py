"""Unit gate negative controls use only disposable checkout/report fixtures."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("unit_gate", Path(__file__).parents[1] / "verify-unit-tests.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
SHA = "2f9032732fc8365d19b5badac90f66b10310f500"


class UnitGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "settings.gradle.kts").write_text('include(":android")\ninclude(":jvm")\ninclude(":metadata")\n')
        self.paths = {}
        reports = []
        for module, task, plugin in (("android", "testDebugUnitTest", "libs.plugins.android.library"),
                                     ("jvm", "test", "libs.plugins.kotlin.jvm")):
            directory = self.root / module
            directory.mkdir()
            (directory / "build.gradle.kts").write_text(f"plugins {{ alias({plugin}) }}")
            sources = directory / "src/test/kotlin"
            sources.mkdir(parents=True)
            (sources / "ExampleTest.kt").write_text("class ExampleTest")
            path = directory / f"build/test-results/{task}/TEST-example.xml"
            path.parent.mkdir(parents=True)
            suite = ET.Element("testsuite", tests="1", failures="0", errors="0", skipped="0")
            ET.SubElement(suite, "testcase", classname=f"{module}.ExampleTest", name="example")
            reports.append((path, suite))
            self.paths[module] = path
        gate.configure(self.root)
        gate.gate.begin(self.root, "unit", SHA, "run-1", "1", SHA)
        for path, suite in reports:
            ET.ElementTree(suite).write(path)
        self.stamp()

    def stamp(self):
        gate.record(self.root, SHA, "run-1", "1")

    def problems(self, required=()):
        return gate.verify(self.root, SHA, "run-1", "1", required)[0]

    def test_android_and_jvm_pass_no_source_is_separate(self):
        problems, counts, no_source = gate.verify(self.root, SHA, "run-1", "1")
        self.assertEqual([], problems)
        self.assertEqual({"android": 1, "jvm": 1}, counts)
        self.assertEqual(["metadata"], no_source)

    def test_missing_module_report_fails(self):
        self.paths["jvm"].unlink()
        self.stamp()
        self.assertTrue(any("no connected-test XML" in p for p in self.problems()))

    def test_zero_executed_module_fails(self):
        ET.ElementTree(ET.Element("testsuite", tests="0", failures="0", errors="0", skipped="0")).write(self.paths["jvm"])
        self.stamp()
        self.assertTrue(any("zero executed" in p for p in self.problems()))

    def test_filtered_required_suite_fails(self):
        self.assertTrue(any("required unit suite" in p for p in self.problems(["Missing.RequiredTest"])))

    def test_one_executed_method_cannot_certify_complete_required_suite(self):
        source = self.root / "android/src/test/kotlin/ExampleTest.kt"
        source.write_text('package android\nclass ExampleTest { @Test fun example() {}\n@Test fun `missing regression`() {} }')
        problems = gate.verify(self.root, SHA, "run-1", "1", ["android.ExampleTest"], True)[0]
        self.assertTrue(any("#missing regression: required unit method" in p for p in problems))
        self.assertFalse(any("#example:" in p for p in problems))
        tree = ET.parse(self.paths["android"])
        tree.getroot().set("tests", "2")
        ET.SubElement(tree.getroot(), "testcase", classname="android.ExampleTest", name="missing regression")
        tree.write(self.paths["android"])
        self.stamp()
        self.assertEqual([], gate.verify(self.root, SHA, "run-1", "1", ["android.ExampleTest"], True)[0])

    def test_complete_suite_missing_source_fails(self):
        (self.root / "android/src/test/kotlin/ExampleTest.kt").unlink()
        problems = gate.verify(self.root, SHA, "run-1", "1", ["android.ExampleTest"], True)[0]
        self.assertTrue(any("source missing or ambiguous" in p for p in problems))

    def test_skipped_nonmandatory_unit_fails(self):
        tree = ET.parse(self.paths["jvm"])
        tree.getroot().set("skipped", "1")
        ET.SubElement(tree.getroot()[0], "skipped")
        tree.write(self.paths["jvm"])
        self.stamp()
        self.assertTrue(any("skipped" in p for p in self.problems()))

    def test_stale_sha_and_hash_fail(self):
        path = self.root / "ci-evidence/unit-manifest.json"
        manifest = json.loads(path.read_text())
        manifest["checkoutSha"] = "old"
        path.write_text(json.dumps(manifest))
        self.assertTrue(any("checkoutSha" in p for p in self.problems()))
        self.stamp()
        self.paths["jvm"].write_text(self.paths["jvm"].read_text() + "\n")
        self.assertTrue(any("report paths/content" in p for p in self.problems()))

    def test_duplicate_results_fail_and_unique_count_does_not_inflate(self):
        path = self.paths["jvm"]
        path.with_name("TEST-duplicate.xml").write_bytes(path.read_bytes())
        self.stamp()
        problems, counts, _ = gate.verify(self.root, SHA, "run-1", "1")
        self.assertTrue(any("duplicate execution" in p for p in problems))
        self.assertEqual(1, counts["jvm"])

    def test_inventory_change_cannot_reuse_manifest(self):
        directory = self.root / "metadata"
        directory.mkdir()
        (directory / "build.gradle.kts").write_text("plugins { alias(libs.plugins.kotlin.jvm) }")
        sources = directory / "src/test/kotlin"
        sources.mkdir(parents=True)
        (sources / "NewTest.kt").write_text("class NewTest")
        self.assertTrue(any("NO-SOURCE inventory" in p for p in self.problems()))


if __name__ == "__main__":
    unittest.main()
