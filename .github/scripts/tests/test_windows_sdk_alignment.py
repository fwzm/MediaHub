import json
from pathlib import Path
import re
import unittest


class WindowsSdkAlignmentTest(unittest.TestCase):
    def test_every_windows_job_installs_the_repository_pinned_sdk(self):
        root = Path(__file__).resolve().parents[3]
        pinned = json.loads((root / "global.json").read_text(encoding="utf-8"))["sdk"]["version"]
        workflow = (root / ".github/workflows/windows-ci.yml").read_text(encoding="utf-8")
        versions = re.findall(r"^\s+dotnet-version:\s*['\"]?([^'\"\s#]+)", workflow, re.MULTILINE)
        self.assertGreaterEqual(len(versions), 2, "Both build and interoperability jobs need an SDK")
        self.assertEqual([pinned] * len(versions), versions)
