import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "prepare-visual-emulator.sh"


class VisualEmulatorPreparationTest(unittest.TestCase):
    def invoke(self, home="com.android.launcher3/.Launcher", serial="emulator-5554",
               provisioned="1", has_setup="1", wrapper=False, gradle_exit="0", record_exit="0"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake_bin = root / "bin"
            fake_bin.mkdir()
            adb = fake_bin / "adb"
            adb.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$FIXTURE_COMMANDS"
case "$*" in
  *"shell settings get global device_provisioned"|*"shell settings get secure user_setup_complete") printf '%s\\n' "$FIXTURE_PROVISIONED" ;;
  *"shell pm path com.google.android.sdksetup") if [[ "$FIXTURE_HAS_SETUP" == 1 ]]; then echo 'package:/system/app/sdksetup.apk'; fi ;;
  *"shell pm disable-user --user 0 com.google.android.sdksetup") echo 'Package com.google.android.sdksetup new state: disabled-user' ;;
  *"shell am start -W"*) printf 'Status: ok\\nActivity: %s\\n' "$FIXTURE_HOME" ;;
  *"shell dumpsys activity activities") printf 'topResumedActivity=ActivityRecord{%s}\\n' "$FIXTURE_HOME" ;;
  *"shell dumpsys window windows") printf 'mCurrentFocus=Window{%s}\\n' "$FIXTURE_HOME" ;;
  *"shell getprop ro.build.version.sdk") echo 32 ;;
esac
''', encoding="utf-8", newline="\n")
            adb.chmod(0o755)
            script = root / "prepare.sh"
            script.write_text(SCRIPT.read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
            commands = root / "commands.txt"
            if wrapper:
                scripts = root / ".github/scripts"
                scripts.mkdir(parents=True)
                (scripts / "prepare-visual-emulator.sh").write_text(SCRIPT.read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
                script.write_text((SCRIPT.parent / "run-visual-validation.sh").read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
                gradle = '''printf "GRADLE %s\\n" "$*" >> "$FIXTURE_COMMANDS"
report_dir="app/build/outputs/androidTest-results/connected/debug"
case "$*" in
  *BackupUserFlow*) mkdir -p "$report_dir"; printf backup > "$report_dir/TEST-result.xml" ;;
  *CinematicVisualTest*) mkdir -p "$report_dir"; printf cinematic > "$report_dir/TEST-result.xml" ;;
esac
exit "$FIXTURE_GRADLE_EXIT"'''
                for fake, content in ((root / "gradlew", gradle),
                                      (fake_bin / "python3", 'echo RECORD >> "$FIXTURE_COMMANDS"; exit "$FIXTURE_RECORD_EXIT"'),
                                      (fake_bin / "cp", 'printf "cp %s\\n" "$*" >> "$FIXTURE_COMMANDS"; command -p cp "$@"')):
                    fake.write_text("#!/usr/bin/env bash\n" + content + "\n", encoding="utf-8", newline="\n")
                    fake.chmod(0o755)
            env = os.environ.copy()
            env.update(ANDROID_SERIAL=serial, FIXTURE_HOME=home,
                       FIXTURE_PROVISIONED=provisioned, FIXTURE_HAS_SETUP=has_setup,
                       FIXTURE_GRADLE_EXIT=gradle_exit, FIXTURE_RECORD_EXIT=record_exit,
                       EXPECTED_HEAD="a" * 40, GITHUB_RUN_ID="fixture-run", GITHUB_RUN_ATTEMPT="1",
                       FIXTURE_COMMANDS=str(commands), PATH=str(fake_bin) + os.pathsep + env["PATH"])
            bash = Path(r"C:\Program Files\Git\bin\bash.exe") if os.name == "nt" else None
            executable = str(bash) if bash and bash.exists() else shutil.which("bash")
            self.assertIsNotNone(executable, "actual Bash required for CI bootstrap fixture")
            result = subprocess.run([executable, "prepare.sh"] + (["32"] if wrapper else []), cwd=root, env=env,
                                    capture_output=True, text=True, timeout=15)
            report = root / "app/build/outputs/androidTest-results/connected/debug/TEST-result.xml"
            result.report_text = report.read_text(encoding="utf-8") if report.exists() else ""
            backup_report = root / "ci-evidence/backup-report/TEST-result.xml"
            result.backup_report_text = backup_report.read_text(encoding="utf-8") if backup_report.exists() else ""
            return result, commands.read_text(encoding="utf-8") if commands.exists() else ""

    def test_setup_home_is_rejected_even_when_status_and_resumed_match(self):
        result, _ = self.invoke(home="com.google.android.sdksetup/.DefaultActivity")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)

    def test_real_home_requires_provision_readback_and_disables_sdk_setup_before_launch(self):
        result, commands = self.invoke()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("settings put global device_provisioned 1", commands)
        self.assertIn("settings put secure user_setup_complete 1", commands)
        self.assertLess(commands.index("pm disable-user"), commands.index("am start -W"))

    def test_failed_provision_readback_blocks_before_home_launch(self):
        result, commands = self.invoke(provisioned="0")
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("am start -W", commands)

    def test_missing_sdk_setup_package_does_not_require_disabling_other_packages(self):
        result, commands = self.invoke(has_setup="0")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("pm disable-user", commands)

    def test_physical_serial_is_rejected_without_any_adb_action(self):
        result, commands = self.invoke(serial="fixture-physical")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", commands)

    def test_wrapper_rejects_physical_before_device_setup_or_test_install(self):
        result, commands = self.invoke(serial="fixture-physical", wrapper=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", commands)

    def test_single_process_wrapper_preserves_success_exit(self):
        result, commands = self.invoke(wrapper=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertLess(commands.index("GRADLE"), commands.index("RECORD"))

    def test_wrapper_records_then_preserves_failed_gradle_exit(self):
        result, commands = self.invoke(wrapper=True, gradle_exit="7")
        self.assertEqual(7, result.returncode, result.stdout + result.stderr)
        self.assertIn("RECORD", commands)

    def test_wrapper_record_failure_cannot_be_hidden_by_green_gradle(self):
        result, _ = self.invoke(wrapper=True, record_exit="8")
        self.assertEqual(8, result.returncode, result.stdout + result.stderr)

    def test_wrapper_forces_test_tasks_without_rebuilding_all_dependencies(self):
        result, commands = self.invoke(wrapper=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("--rerun-tasks", commands)
        self.assertEqual(7, len(re.findall(r"(?<!\S)--rerun(?:\s|$)", commands)), commands)
        self.assertEqual(3, commands.count("GRADLE"), commands)
        self.assertEqual(2, commands.count(":app:connectedDebugAndroidTest"), commands)
        self.assertIn("class=com.mediahub.app.visual.CinematicVisualTest", commands)
        self.assertEqual("backup", result.backup_report_text)
        self.assertEqual("backup", result.report_text)

    def test_wrapper_setup_failure_prevents_gradle_and_record(self):
        result, commands = self.invoke(wrapper=True, home="com.google.android.sdksetup/.DefaultActivity")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("GRADLE", commands)
        self.assertNotIn("RECORD", commands)
