"""Local disposable Git fixtures. No GitHub connection or scheduler registration."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(os.environ.get("NIGHTLY_SYNC_UNDER_TEST", ROOT / "scripts/github-nightly-sync.ps1"))
PWSH = shutil.which("pwsh")


class SyncSafetyTest(unittest.TestCase):
    def setUp(self):
        self.parent = Path(os.environ.get("MH_SYNC_TEST_TEMP", tempfile.gettempdir())).resolve()
        self.temp = tempfile.TemporaryDirectory(prefix="mh-sync-fixture-", dir=self.parent)
        self.root = Path(self.temp.name).resolve()
        assert self.root.is_relative_to(self.parent)
        self.addCleanup(self.temp.cleanup)
        self.repo = self.root / "repo"
        self.remote = self.root / "remote.git"
        self.repo.mkdir()
        self.git("init", "-q", "-b", "codex/fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "user.name", "Controlled fixture")
        (self.repo / "README.md").write_text("# Fixture\n", encoding="utf-8")
        self.git("add", "README.md")
        self.git("commit", "-qm", "fixture initial")
        subprocess.run(["git", "init", "--bare", "-q", str(self.remote)], check=True)
        self.git("remote", "add", "origin", str(self.remote))

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args]).decode("utf-8").strip()

    def run_script(self, *args):
        return subprocess.run([PWSH, "-NoProfile", "-File", str(SCRIPT), "-RepoRoot", str(self.repo), *args],
                              capture_output=True, text=True, encoding="utf-8", timeout=30)

    def snapshot(self, directory=None):
        directory = directory or self.root
        return {str(path.relative_to(directory)): path.read_bytes()
                for path in directory.rglob("*") if path.is_file()}

    def assert_success(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_existing_staged_asset_is_never_committed_or_overwritten(self):
        asset = self.repo / "asset.txt"
        asset.write_text("owned fixture asset", encoding="utf-8")
        self.git("add", "asset.txt")
        before = self.git("rev-parse", "HEAD")
        result = self.run_script(*([] if os.environ.get("MH_LEGACY_BASELINE") else ["-Apply"]))
        self.assertEqual(before, self.git("rev-parse", "HEAD"), result.stdout + result.stderr)
        self.assertEqual("asset.txt", self.git("diff", "--cached", "--name-only"))
        self.assertEqual("owned fixture asset", asset.read_text(encoding="utf-8"))
        self.assertNotEqual(0, result.returncode, "Apply must explicitly refuse tracked/index WIP")

    def test_default_preview_preserves_all_files_refs_index_and_remote(self):
        self.git("push", "-qu", "origin", "HEAD")
        before = self.snapshot()
        self.assert_success(self.run_script())
        self.assertEqual(before, self.snapshot())

    def test_explicit_dry_run_is_read_only_even_with_staged_asset(self):
        (self.repo / "asset.txt").write_text("staged", encoding="utf-8")
        self.git("add", "asset.txt")
        before = self.snapshot()
        self.assert_success(self.run_script("-DryRun"))
        self.assertEqual(before, self.snapshot())

    def test_apply_commits_only_readme_and_keeps_untracked_asset(self):
        (self.repo / "asset.txt").write_text("untracked", encoding="utf-8")
        self.assert_success(self.run_script("-Apply"))
        self.assertEqual("README.md", self.git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD"))
        self.assertEqual("?? asset.txt", self.git("status", "--porcelain"))
        self.assertEqual("untracked", (self.repo / "asset.txt").read_text(encoding="utf-8"))
        self.assertEqual(self.git("rev-parse", "HEAD"), self.git("rev-parse", "origin/codex/fixture"))

    def test_linked_worktree_preview_supports_git_file_without_writes(self):
        linked = self.root / "linked"
        self.git("worktree", "add", "-qb", "codex/linked", str(linked))
        self.assertTrue((linked / ".git").is_file())
        self.repo = linked
        before = self.snapshot()
        self.assert_success(self.run_script())
        self.assertEqual(before, self.snapshot())

    def test_protected_main_ref_is_unchanged_by_apply(self):
        self.git("branch", "-m", "main")  # Only this disposable fixture.
        before = self.git("rev-parse", "main")
        self.assert_success(self.run_script("-Apply"))
        self.assertEqual(before, self.git("rev-parse", "main"))
        self.assertTrue(self.git("branch", "--show-current").startswith("codex/nightly-"))
        self.assertNotEqual(before, self.git("rev-parse", "HEAD"))

    def test_repeat_apply_does_not_generate_another_sync_commit(self):
        self.assert_success(self.run_script("-Apply"))
        before = self.git("rev-parse", "HEAD")
        self.assert_success(self.run_script("-Apply"))
        self.assertEqual(before, self.git("rev-parse", "HEAD"))
        self.assertTrue((self.repo / ".git/github-sync-last-check").is_file())

    def test_checkpoint_catches_previous_day_work_after_midnight(self):
        initial_time = datetime.now(timezone.utc) - timedelta(days=3)
        initial_env = dict(os.environ, GIT_AUTHOR_DATE=initial_time.isoformat(),
                           GIT_COMMITTER_DATE=initial_time.isoformat())
        subprocess.run(["git", "-C", str(self.repo), "commit", "--amend", "--no-edit",
                        "--date", initial_time.isoformat()], env=initial_env, check=True,
                       stdout=subprocess.PIPE)
        self.git("push", "-qu", "origin", "HEAD")
        yesterday = datetime.now(timezone.utc) - timedelta(hours=12)
        fixture_env = dict(os.environ, GIT_AUTHOR_DATE=yesterday.isoformat(),
                           GIT_COMMITTER_DATE=yesterday.isoformat())
        subprocess.run(["git", "-C", str(self.repo), "commit", "--allow-empty", "-qm", "previous work"],
                       env=fixture_env, check=True)
        self.git("push", "-q", "origin", "HEAD")
        marker = self.repo / ".git/github-sync-last-check"
        marker.write_text((yesterday - timedelta(hours=1)).isoformat(), encoding="utf-8")
        result = self.run_script()
        self.assert_success(result)
        self.assertIn("Window commits=1; unpushed=0", result.stdout)

    def test_future_checkpoint_falls_back_to_24_hour_window(self):
        self.git("push", "-qu", "origin", "HEAD")
        (self.repo / ".git/github-sync-last-check").write_text(
            (datetime.now(timezone.utc) + timedelta(days=5)).isoformat(), encoding="utf-8")
        result = self.run_script()
        self.assert_success(result)
        self.assertIn("Window commits=1; unpushed=0", result.stdout)

    def test_readme_does_not_copy_commit_subject_secrets(self):
        private_subject = "fixture-private-subject-sentinel"
        self.git("commit", "--allow-empty", "-qm", private_subject)
        self.assert_success(self.run_script("-Apply"))
        self.assertNotIn(private_subject, (self.repo / "README.md").read_text(encoding="utf-8"))

    def test_dirty_tracked_readme_is_preserved(self):
        path = self.repo / "README.md"
        path.write_text("owned README edit", encoding="utf-8")
        before = self.git("rev-parse", "HEAD")
        result = self.run_script("-Apply")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.git("rev-parse", "HEAD"))
        self.assertEqual("owned README edit", path.read_text(encoding="utf-8"))

    def test_untracked_readme_is_preserved(self):
        self.git("rm", "--cached", "README.md")
        self.git("commit", "-qm", "fixture without tracked README")
        path = self.repo / "README.md"
        original = path.read_bytes()
        result = self.run_script("-Apply")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(original, path.read_bytes())
        self.assertEqual("?? README.md", self.git("status", "--porcelain"))

    def test_live_lock_is_not_deleted_or_replaced(self):
        lock = self.repo / ".git/github-sync.lock"
        ready = self.root / "ready.txt"
        escaped_lock = str(lock).replace("'", "''")
        escaped_ready = str(ready).replace("'", "''")
        command = (f"$handle=[IO.File]::Open('{escaped_lock}','OpenOrCreate','Write','None'); "
                   f"[IO.File]::WriteAllText('{escaped_ready}','ready'); "
                   "try { [Console]::ReadLine() | Out-Null } finally { $handle.Dispose() }")
        owner = subprocess.Popen([PWSH, "-NoProfile", "-Command", command], stdin=subprocess.PIPE,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            import time
            deadline = time.monotonic() + 10
            while not ready.exists() and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertTrue(ready.exists(), "fixture lock owner did not start")
            before = self.git("rev-parse", "HEAD")
            result = self.run_script("-Apply")
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(before, self.git("rev-parse", "HEAD"))
            self.assertTrue(lock.exists())
            self.assertFalse((self.repo / ".git/github-sync-last-check").exists())
        finally:
            owner.communicate("\n", timeout=10)


if __name__ == "__main__":
    unittest.main()
