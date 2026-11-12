"""收编重复的 GitHub 同步工具：唯一入口断言与残留引用守卫。

全部断言跑在真实工作树上；语法检查调用真实 pwsh 7 / Windows PowerShell 5.1 的
Language.Parser，不 stub。行为用例只用一次性本地 Git 仓库，不连 GitHub、不读写远端。
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"
DAILY = SCRIPTS / "sync-github-daily.ps1"
NIGHTLY = SCRIPTS / "github-nightly-sync.ps1"
README = ROOT / "README.md"
DOC = SCRIPTS / "SYNC_UTILITY.md"
PWSH = shutil.which("pwsh")
PS51 = shutil.which("powershell")
DAILY_NAME = "sync-github-daily"
BOM = b"\xef\xbb\xbf"


def tracked_files():
    out = subprocess.check_output(["git", "-C", str(ROOT), "ls-files"], text=True, encoding="utf-8")
    return [ROOT / line for line in out.splitlines() if line]


def read_text(path):
    return Path(path).read_text(encoding="utf-8-sig")


def parse_errors(shell, script):
    escaped = str(script).replace("'", "''")
    command = (
        "$t=$null;$e=$null;"
        f"[void][System.Management.Automation.Language.Parser]::ParseFile('{escaped}',[ref]$t,[ref]$e);"
        "if($e.Count -gt 0){$e|ForEach-Object{$_.Message};exit 2};'PARSE_OK'"
    )
    return subprocess.run([shell, "-NoProfile", "-NonInteractive", "-Command", command],
                          capture_output=True, text=True, encoding="utf-8", timeout=120)


class SyncUtilityConvergenceTest(unittest.TestCase):
    """一份职责只留一个入口，且不留悬空引用。"""

    def test_daily_script_is_removed(self):
        self.assertFalse(DAILY.exists(), "重复的每日同步脚本应已收敛删除")

    def test_only_one_sync_entry_script_remains(self):
        names = sorted(p.name for p in SCRIPTS.glob("*.ps1") if "sync" in p.name.lower())
        self.assertEqual(["github-nightly-sync.ps1"], names)

    def test_no_tracked_file_still_references_the_removed_script(self):
        own = Path(__file__).resolve()
        hits = []
        for path in tracked_files():
            if path.resolve() == own or not path.is_file():
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            if DAILY_NAME in text:
                hits.append(str(path.relative_to(ROOT)).replace(os.sep, "/"))
        self.assertEqual([], hits, "仍有文件引用已删除的脚本")

    def test_readme_has_no_orphan_windows_sync_block(self):
        self.assertNotIn("WINDOWS-SYNC", read_text(README))

    def test_readme_keeps_exactly_one_recent_updates_block(self):
        headings = [line.strip() for line in read_text(README).splitlines()
                    if line.strip().startswith("#") and "最近更新" in line]
        self.assertEqual(1, len(headings), headings)

    def test_sync_doc_records_single_entry_decision(self):
        text = read_text(DOC)
        self.assertIn("github-nightly-sync.ps1", text)
        self.assertNotIn(DAILY_NAME, text)
        self.assertIn("唯一入口", text)

    def test_remaining_sync_script_keeps_utf8_bom(self):
        self.assertTrue(NIGHTLY.read_bytes().startswith(BOM), "同步脚本必须保留 UTF-8 BOM")

    @unittest.skipUnless(PWSH, "需要 pwsh 7")
    def test_nightly_script_parses_under_pwsh7(self):
        result = parse_errors(PWSH, NIGHTLY)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PARSE_OK", result.stdout)

    @unittest.skipUnless(PS51, "需要 Windows PowerShell 5.1")
    def test_nightly_script_parses_under_windows_powershell_51(self):
        result = parse_errors(PS51, NIGHTLY)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PARSE_OK", result.stdout)

    @unittest.skipUnless(PWSH, "需要 pwsh 7")
    def test_sole_entry_still_previews_without_writing(self):
        parent = Path(os.environ.get("MH_SYNC_TEST_TEMP", tempfile.gettempdir())).resolve()
        with tempfile.TemporaryDirectory(prefix="mh-sync-converge-", dir=parent) as temp:
            repo = Path(temp) / "repo"
            repo.mkdir()

            def git(*args):
                return subprocess.check_output(["git", "-C", str(repo), *args],
                                               text=True, encoding="utf-8").strip()

            git("init", "-q", "-b", "codex/fixture")
            git("config", "user.email", "fixture@example.invalid")
            git("config", "user.name", "Controlled fixture")
            (repo / "README.md").write_text("# Fixture\n", encoding="utf-8")
            git("add", "README.md")
            git("commit", "-qm", "fixture initial")
            before = {p.relative_to(repo).as_posix(): p.read_bytes()
                      for p in repo.rglob("*") if p.is_file()}
            result = subprocess.run([PWSH, "-NoProfile", "-File", str(NIGHTLY), "-RepoRoot", str(repo)],
                                    capture_output=True, text=True, encoding="utf-8", timeout=120)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            after = {p.relative_to(repo).as_posix(): p.read_bytes()
                     for p in repo.rglob("*") if p.is_file()}
            self.assertEqual(before, after, "默认预览不得写文件、引用或远端")


if __name__ == "__main__":
    unittest.main()
