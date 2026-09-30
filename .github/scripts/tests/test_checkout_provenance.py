"""CLI negatives use actual disposable Git repositories, with no shared checkout edits."""
from datetime import datetime, timedelta, timezone
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

SCRIPTS = Path(__file__).parents[1]
spec = importlib.util.spec_from_file_location("timestamp_gate", SCRIPTS / "verify-visual-tests.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class CheckoutProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Isolated Gate Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        (self.root / ".gitignore").write_text("build/\n")
        (self.root / "source.kt").write_text("class Source\n")
        (self.root / "settings.gradle.kts").write_text('include(":metadata")\n')
        self.git("add", ".")
        self.git("commit", "-qm", "initial isolated fixture")
        self.sha = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args], text=True, stderr=subprocess.STDOUT)

    def cli(self, kind, operation):
        args = [sys.executable, str(SCRIPTS / f"verify-{kind}-tests.py"), f"--{operation}",
                "--root", str(self.root), "--sha", self.sha, "--run-id", "fixture-run", "--attempt", "1"]
        if kind == "visual":
            args += ["--api", "32"]
            if operation == "record":
                args += ["--device-api", "32"]
        if operation == "begin":
            args += ["--report-timezone", "UTC"]
        return subprocess.run(args, text=True, capture_output=True)

    def begin_both(self):
        for kind in ("unit", "visual"):
            result = self.cli(kind, "begin")
            self.assertEqual(0, result.returncode, result.stderr)

    def assert_both_reject(self, operation, text):
        for kind in ("unit", "visual"):
            with self.subTest(kind=kind):
                result = self.cli(kind, operation)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(text, result.stderr)

    def test_begin_refuses_modified_tracked_source(self):
        (self.root / "source.kt").write_text("class Modified\n")
        self.assert_both_reject("begin", "modified/staged")

    def test_begin_refuses_nonignored_untracked_source(self):
        (self.root / "new-source.kt").write_text("class Untracked\n")
        self.assert_both_reject("begin", "untracked source")

    def test_record_rechecks_changed_commit(self):
        self.begin_both()
        (self.root / "source.kt").write_text("class NewCommit\n")
        self.git("add", "source.kt")
        self.git("commit", "-qm", "moved source revision")
        self.assert_both_reject("record", "checkout differs")

    def test_record_refuses_staged_change_after_begin(self):
        self.begin_both()
        (self.root / "source.kt").write_text("class Staged\n")
        self.git("add", "source.kt")
        self.assert_both_reject("record", "modified/staged")

    def test_record_refuses_untracked_source_after_begin(self):
        self.begin_both()
        (self.root / "new-source.kt").write_text("class Untracked\n")
        self.assert_both_reject("record", "untracked source")

    def test_generated_ledgers_and_ignored_build_do_not_dirty_sources(self):
        self.begin_both()
        (self.root / "build").mkdir()
        (self.root / "build/output.bin").write_bytes(b"generated")
        (self.root / "ci-evidence/revision.txt").write_text("generated evidence\n")
        (self.root / "ci-validation").mkdir()
        (self.root / "ci-validation/revision.json").write_text("{}")
        for kind in ("unit", "visual"):
            result = self.cli(kind, "record")
            self.assertEqual(0, result.returncode, result.stderr)

    def test_tracked_file_under_generated_directory_is_still_checked(self):
        directory = self.root / "ci-evidence"
        directory.mkdir()
        source = directory / "tracked-source.kt"
        source.write_text("class Tracked\n")
        self.git("add", "ci-evidence/tracked-source.kt")
        self.git("commit", "-qm", "tracked fixture under reserved directory")
        self.sha = self.git("rev-parse", "HEAD").strip()
        self.begin_both()
        source.write_text("class ChangedTracked\n")
        self.assert_both_reject("record", "modified/staged")


class ReportTimezoneTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sha = "f" * 40
        gate.begin(self.root, 32, self.sha, "run", "1", self.sha, "+08:00")
        self.path = self.root / "provider/webdav/build/outputs/androidTest-results/connected/TEST-time.xml"
        self.path.parent.mkdir(parents=True)

    def write(self, timestamp):
        ET.ElementTree(ET.Element("testsuite", tests="0", failures="0", errors="0", skipped="0",
                                 timestamp=timestamp)).write(self.path)

    def record(self):
        gate.record(self.root, 32, self.sha, "run", "1", 32)

    def test_fresh_windows_plus8_naive_timestamp_passes(self):
        stamp = datetime.now(timezone(timedelta(hours=8))).replace(tzinfo=None).isoformat()
        self.write(stamp)
        self.record()

    def test_copied_hours_old_plus8_timestamp_fails(self):
        stamp = (datetime.now(timezone(timedelta(hours=8))) - timedelta(hours=2)).replace(tzinfo=None).isoformat()
        self.write(stamp)
        with self.assertRaisesRegex(ValueError, "predates"):
            self.record()

    def test_future_timestamp_fails(self):
        stamp = (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat()
        self.write(stamp)
        with self.assertRaisesRegex(ValueError, "future"):
            self.record()

    def test_explicit_utc_ledger_accepts_fresh_naive_utc(self):
        gate.begin(self.root, 32, self.sha, "run", "1", self.sha, "UTC")
        self.write(datetime.now(timezone.utc).replace(tzinfo=None).isoformat())
        self.record()


if __name__ == "__main__":
    unittest.main()
