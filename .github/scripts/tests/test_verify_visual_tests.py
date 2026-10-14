"""Isolated XML fixtures: never rewrite actual device reports."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("visual_gate", Path(__file__).parents[1] / "verify-visual-tests.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
SHA = "2f9032732fc8365d19b5badac90f66b10310f500"


class EvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.paths = {}
        self.build(32)

    def build(self, api):
        for path in self.root.rglob("TEST-*.xml"):
            path.unlink()
        gate.begin(self.root, api, SHA, "run-1", "1", SHA)
        grouped = {module: [] for module in gate.MODULES}
        for key in sorted(gate.required_tests(api)):
            module = next(m for m in gate.MODULES if m.replace("/", ".") in key[0])
            grouped[module].append(key)
        for module, keys in grouped.items():
            suite = ET.Element("testsuite", tests=str(len(keys)), failures="0", errors="0", skipped="0")
            for cls, name in keys:
                ET.SubElement(suite, "testcase", classname=cls, name=name)
            path = self.root / module / "build/outputs/androidTest-results/connected/TEST-fixture.xml"
            path.parent.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(suite).write(path)
            self.paths[module] = path
        self.stamp(api)

    def stamp(self, api=32):
        gate.record(self.root, api, SHA, "run-1", "1", api)

    def problems(self, api=32):
        return gate.verify(self.root, api, SHA, "run-1", "1")

    def edit(self, module, mutation):
        path = self.paths[module]
        tree = ET.parse(path)
        mutation(tree.getroot())
        tree.write(path)
        self.stamp()

    def test_complete_reports_pass_both_apis(self):
        self.assertEqual([], self.problems())
        self.build(36)
        self.assertEqual([], self.problems(36))

    def test_missing_module_fails(self):
        self.paths["provider/webdav"].unlink()
        self.stamp()
        self.assertTrue(any("no connected-test XML" in p for p in self.problems()))

    def test_missing_each_parser_assertion_fails(self):
        original = self.paths["provider/webdav"].read_bytes()
        for cls, name in sorted(k for k in gate.COMMON_TESTS if "ParserDeviceTest" in k[0]):
            with self.subTest(name=name):
                self.paths["provider/webdav"].write_bytes(original)
                def remove(suite):
                    suite.remove(next(c for c in suite if c.get("name") == name))
                    suite.set("tests", str(len(suite)))
                self.edit("provider/webdav", remove)
                self.assertTrue(any(name in p and "no passing execution" in p for p in self.problems()))

    def test_missing_new_room_or_production_route_case_fails(self):
        for module, name in (
            ("core/database", "historicalSchemasMigrateAndReopenThroughActualRoom"),
            ("feature/settings", "productionSettingsInfoDensityPersistsAndBackupEntryDispatches"),
            ("feature/player", "productionPlayerInformationAndSubtitleActionsPreservePlaybackSession"),
        ):
            with self.subTest(name=name):
                self.build(32)
                def remove(suite):
                    suite.remove(next(c for c in suite if c.get("name") == name))
                    suite.set("tests", str(len(suite)))
                self.edit(module, remove)
                self.assertTrue(any(name in p and "no passing execution" in p for p in self.problems()))

    def test_zero_tests_fails_even_with_valid_manifest(self):
        for module in gate.MODULES:
            ET.ElementTree(ET.Element("testsuite", tests="0", failures="0", errors="0", skipped="0")).write(self.paths[module])
        self.stamp()
        self.assertEqual(len(gate.required_tests(32)), sum("no passing execution" in p for p in self.problems()))

    def test_skipped_mandatory_fails(self):
        def skip(suite):
            ET.SubElement(suite[0], "skipped")
            suite.set("skipped", "1")
        self.edit("provider/webdav", skip)
        self.assertTrue(any("mandatory assertion was skipped" in p for p in self.problems()))

    def test_failure_and_error_fail(self):
        for tag, field in (("failure", "failures"), ("error", "errors")):
            with self.subTest(tag=tag):
                self.build(32)
                def fail(suite):
                    ET.SubElement(suite[0], tag)
                    suite.set(field, "1")
                self.edit("provider/webdav", fail)
                self.assertTrue(any(": failed" in p for p in self.problems()))

    def test_missing_manifest_fails(self):
        (self.root / gate.MANIFEST).unlink()
        self.assertTrue(any("missing/invalid" in p for p in self.problems()))

    def test_stale_sha_run_or_attempt_fails(self):
        path = self.root / gate.MANIFEST
        for field in ("checkoutSha", "runId", "runAttempt"):
            with self.subTest(field=field):
                self.stamp()
                data = json.loads(path.read_text())
                data[field] = "old"
                path.write_text(json.dumps(data))
                self.assertTrue(any(field in p for p in self.problems()))

    def test_api_metadata_mixing_fails(self):
        self.assertTrue(any("api differs" in p for p in self.problems(36)))

    def test_actual_device_api_mismatch_cannot_be_recorded(self):
        with self.assertRaises(ValueError):
            gate.record(self.root, 32, SHA, "run-1", "1", 36)

    def test_begin_requires_clean_reports_and_actual_checkout(self):
        with self.assertRaisesRegex(ValueError, "must be empty"):
            gate.begin(self.root, 32, SHA, "run-1", "1", SHA)
        with self.assertRaisesRegex(ValueError, "Git checkout"):
            gate.begin(self.root, 32, SHA, "run-1", "1", "different-checkout")

    def test_record_without_execution_start_refuses_relabeling(self):
        gate.start_path(self.root).unlink()
        with self.assertRaises(OSError):
            self.stamp()

    def test_old_xml_cannot_be_rerecorded_with_fresh_labels(self):
        path = self.paths["provider/webdav"]
        started = json.loads(gate.start_path(self.root).read_text())["startedNs"]
        os.utime(path, ns=(started - 1_000_000_000, started - 1_000_000_000))
        with self.assertRaisesRegex(ValueError, "predates"):
            self.stamp()

    def test_copied_old_suite_timestamp_cannot_be_rerecorded(self):
        path = self.paths["provider/webdav"]
        tree = ET.parse(path)
        tree.getroot().set("timestamp", "2000-01-01T00:00:00")
        tree.write(path)
        with self.assertRaisesRegex(ValueError, "suite timestamp predates"):
            self.stamp()

    def test_stale_or_changed_report_hash_fails(self):
        with self.paths["provider/webdav"].open("a") as output:
            output.write("\n")
        self.assertTrue(any("report paths/content" in p for p in self.problems()))

    def test_duplicate_execution_is_rejected_not_counted_again(self):
        path = self.paths["provider/webdav"]
        path.with_name("TEST-duplicate.xml").write_bytes(path.read_bytes())
        self.stamp()
        self.assertTrue(any("duplicate execution (2)" in p for p in self.problems()))

    def test_both_api_xml_sets_in_one_root_fail(self):
        path = self.paths["core/ui"]
        api32 = path.read_bytes()
        self.build(36)
        path.with_name("TEST-api32.xml").write_bytes(api32)
        self.stamp(36)
        self.assertTrue(any("duplicate execution" in p for p in self.problems(36)))

    def test_summary_count_mismatch_fails(self):
        self.edit("provider/webdav", lambda suite: suite.set("tests", "999"))
        self.assertTrue(any("suite test count" in p for p in self.problems()))

    def test_summary_only_skip_without_case_marker_fails(self):
        self.edit("provider/webdav", lambda suite: suite.set("skipped", "1"))
        self.assertTrue(any("suite skipped count" in p for p in self.problems()))

    def test_invalid_xml_and_counts_fail(self):
        self.paths["provider/webdav"].write_text("<invalid")
        self.stamp()
        self.assertTrue(any("invalid report" in p for p in self.problems()))
        self.build(32)
        self.edit("provider/webdav", lambda suite: suite.set("failures", "not-a-count"))
        self.assertTrue(any("invalid suite counts" in p for p in self.problems()))


if __name__ == "__main__":
    unittest.main()
