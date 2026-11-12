"""Abilities absorbed from the ported utility, exercised end to end.

These complement ``test_github_sync_safety.py`` (the locked main baseline of 13
tests) and never weaken its assertions. Every case drives the real script with a
real ``pwsh`` process against a disposable local Git repository.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(os.environ.get("NIGHTLY_SYNC_UNDER_TEST", ROOT / "scripts/github-nightly-sync.ps1"))
PWSH = shutil.which("pwsh")


class MergedAbilitiesTest(unittest.TestCase):
    def setUp(self):
        self.parent = Path(os.environ.get("MH_SYNC_TEST_TEMP", tempfile.gettempdir())).resolve()
        self.temp = tempfile.TemporaryDirectory(prefix="mh-sync-merged-", dir=self.parent)
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

    def script_args(self, *args):
        return [PWSH, "-NoProfile", "-File", str(SCRIPT), "-RepoRoot", str(self.repo), *args]

    def run_script(self, *args):
        return subprocess.run(self.script_args(*args), capture_output=True, text=True,
                              encoding="utf-8", timeout=60)

    def start_script(self, *args):
        return subprocess.Popen(self.script_args(*args), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, encoding="utf-8")

    def snapshot(self, directory=None):
        directory = directory or self.root
        return {str(path.relative_to(directory)): path.read_bytes()
                for path in directory.rglob("*") if path.is_file()}

    def assert_success(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def hold_lock(self):
        """Occupy the real exclusive handle from another process, like a live peer."""
        lock = self.repo / ".git/github-sync.lock"
        ready = self.root / "ready.txt"
        escaped_lock = str(lock).replace("'", "''")
        escaped_ready = str(ready).replace("'", "''")
        command = (f"$handle=[IO.File]::Open('{escaped_lock}','OpenOrCreate','Write','None'); "
                   f"[IO.File]::WriteAllText('{escaped_ready}','ready'); "
                   "try { [Console]::ReadLine() | Out-Null } finally { $handle.Dispose() }")
        owner = subprocess.Popen([PWSH, "-NoProfile", "-Command", command], stdin=subprocess.PIPE,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        deadline = time.monotonic() + 10
        while not ready.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertTrue(ready.exists(), "fixture lock owner did not start")
        self.addCleanup(owner.communicate, "\n", 10)
        return lock

    # --- exit 3 -----------------------------------------------------------
    def test_staged_non_readme_path_exits_3_and_writes_nothing(self):
        (self.repo / "asset.txt").write_text("staged asset", encoding="utf-8")
        self.git("add", "asset.txt")
        head = self.git("rev-parse", "HEAD")
        result = self.run_script("-Apply")
        self.assertEqual(3, result.returncode, result.stdout + result.stderr)
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertEqual("asset.txt", self.git("diff", "--cached", "--name-only"))
        self.assertEqual("staged asset", (self.repo / "asset.txt").read_text(encoding="utf-8"))
        self.assertFalse((self.repo / ".git/github-sync-last-check").exists())

    def test_exit_3_keeps_a_staged_readme_visible_but_uncommitted(self):
        (self.repo / "asset.txt").write_text("staged asset", encoding="utf-8")
        self.git("add", "asset.txt")
        readme = self.repo / "README.md"
        readme.write_text("# Fixture\n\nuser edit\n", encoding="utf-8")
        result = self.run_script("-Apply")
        self.assertEqual(3, result.returncode, result.stdout + result.stderr)
        self.assertEqual("asset.txt", self.git("diff", "--cached", "--name-only"))
        self.assertIn("user edit", readme.read_text(encoding="utf-8"))

    # --- leftover lock handle ---------------------------------------------
    def test_leftover_lock_handle_without_live_owner_is_reclaimed(self):
        lock = self.repo / ".git/github-sync.lock"
        lock.write_text("pid=1;host=gone", encoding="utf-8")
        old = time.time() - 3600
        os.utime(lock, (old, old))
        result = self.run_script("-Apply")
        self.assert_success(result)
        self.assertIn("Reclaimed leftover lock handle without a live owner", result.stdout)
        self.assertEqual("README.md", self.git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD"))
        self.assertTrue(lock.exists())

    def test_two_instances_never_both_write_while_a_peer_holds_the_lock(self):
        lock = self.hold_lock()
        head = self.git("rev-parse", "HEAD")
        peers = [self.start_script("-Apply") for _ in range(2)]
        codes = []
        for peer in peers:
            peer.communicate(timeout=60)
            codes.append(peer.returncode)
        self.assertEqual([code != 0 for code in codes], [True, True], codes)
        self.assertEqual(head, self.git("rev-parse", "HEAD"))
        self.assertTrue(lock.exists())
        self.assertFalse((self.repo / ".git/github-sync-last-check").exists())

    # --- other branches ---------------------------------------------------
    def test_unpushed_other_branch_is_reported_without_any_write(self):
        self.git("push", "-qu", "origin", "HEAD")
        self.git("checkout", "-qb", "codex/local-only")
        (self.repo / "note.txt").write_text("local only", encoding="utf-8")
        self.git("add", "note.txt")
        self.git("commit", "-qm", "local only work")
        self.git("checkout", "-q", "codex/fixture")
        before = self.snapshot()
        result = self.run_script()
        self.assert_success(result)
        self.assertIn("codex/local-only", result.stdout)
        self.assertEqual(before, self.snapshot())

    # --- log file ---------------------------------------------------------
    def test_apply_appends_log_while_preview_creates_none(self):
        self.assert_success(self.run_script())
        log = self.repo / ".git/github-sync.log"
        self.assertFalse(log.exists(), "preview must not create any log file")
        self.assertFalse((self.repo / ".git/github-sync.lock").exists())
        self.assert_success(self.run_script("-Apply"))
        self.assertIn("Window commits=", log.read_text(encoding="utf-8"))

    def test_log_lives_in_the_real_git_dir_of_a_linked_worktree(self):
        linked = self.root / "linked"
        self.git("worktree", "add", "-qb", "codex/linked", str(linked))
        self.repo = linked
        self.assertTrue((linked / ".git").is_file())
        result = self.run_script("-Apply")
        self.assert_success(result)
        git_dir = Path(self.git("rev-parse", "--absolute-git-dir"))
        self.assertNotEqual(self.repo, git_dir)
        self.assertIn("Window commits=", (git_dir / "github-sync.log").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
