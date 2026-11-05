import copy
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import version


class VersionTests(unittest.TestCase):
    def setUp(self):
        self.data = version.read()

    def test_platform_bumps_are_independent_and_keep_build_base(self):
        for platform in version.PLATFORMS:
            for part, expected in (("patch", "2.3.5"), ("minor", "2.4.0"), ("major", "3.0.0")):
                source = copy.deepcopy(self.data)
                source[platform]["version"] = "2.3.4"
                result = version.bump(source, platform, part)
                self.assertEqual(result[platform]["version"], expected)
                for other in version.PLATFORMS:
                    if other != platform:
                        self.assertEqual(result[other], source[other])
                if platform != "backend":
                    self.assertEqual(result[platform]["buildBase"], source[platform]["buildBase"])

    def test_ci_reruns_stable_and_next_run_increases(self):
        for platform in ("ios", "android"):
            first = version.build_number(self.data, platform, 100)
            self.assertEqual(first, version.build_number(self.data, platform, 100))
            following = version.build_number(self.data, platform, 101)
            self.assertLess(tuple(map(int, first.split('.'))), tuple(map(int, following.split('.'))))

    def test_ios_build_component_rollover_and_limits(self):
        self.data["ios"]["buildBase"] = 1
        for run, expected in ((99, "1.0.99"), (100, "1.1.0"), (9999, "1.99.99"), (10000, "2.0.0")):
            self.assertEqual(version.build_number(self.data, "ios", run), expected)
        for platform in ("ios", "android"):
            self.data[platform]["buildBase"] = version.LIMITS[platform]
            version.build_number(self.data, platform)
            with self.assertRaises(ValueError):
                version.build_number(self.data, platform, 1)

    def test_rejects_invalid_versions_numbers_and_schema(self):
        for invalid in ("v1.2.3", "01.2.3", "1.2", "1.2.3-beta", "1.2.3\n", 1):
            data = copy.deepcopy(self.data)
            data["backend"]["version"] = invalid
            with self.assertRaises(ValueError):
                version.validate(data)
        for invalid in (True, 0, -1, 1.5, "1"):
            data = copy.deepcopy(self.data)
            data["android"]["buildBase"] = invalid
            with self.assertRaises(ValueError):
                version.validate(data)
        for invalid in ("0", "-1", "1.5", "01", "1\n", "abc"):
            with self.assertRaises(ValueError):
                version.build_number(self.data, "android", invalid)

    def test_cli_bump_check_and_ci_are_safe_in_checkout(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("scripts/version.py", "versions.json", "ios/recoow.xcodeproj/project.pbxproj", *version.snapshots(self.data)):
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(version.ROOT / name, root / name)

            def run(*args, success=True):
                result = subprocess.run([sys.executable, str(root / "scripts/version.py"), *args], capture_output=True, text=True, env={**os.environ, "GITHUB_RUN_ATTEMPT": "7"})
                self.assertEqual(result.returncode == 0, success, result.stderr)
                return result.stdout

            before = {p: p.read_bytes() for p in root.rglob("*") if p.is_file()}
            run("check")
            run("show")
            tag = f"android/v{self.data['android']['version']}"
            output = run("ci", "android", "--tag", tag, "--run-number", "32")
            self.assertIn(f"build={self.data['android']['buildBase'] + 32}\n", output)
            run("ci", "android", "--tag", "ios/v0.1.0", "--run-number", "32", success=False)
            self.assertEqual(before, {p: p.read_bytes() for p in before})
            run("bump", "backend", "patch")
            run("check")
            after = version.read(root)
            self.assertEqual(after, version.bump(self.data, "backend", "patch"))
            android = root / "android/version.properties"
            android.write_text("versionName=999.0.0\nversionCode=1\n")
            run("check", success=False)
            run("sync")
            run("check")

    def test_widget_override_is_detected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            project = root / "ios/recoow.xcodeproj/project.pbxproj"
            project.parent.mkdir(parents=True)
            project.write_text((version.ROOT / "ios/recoow.xcodeproj/project.pbxproj").read_text().replace("baseConfigurationReference", "CURRENT_PROJECT_VERSION = 7;\nbaseConfigurationReference", 1))
            version.sync(self.data, root)
            with self.assertRaisesRegex(ValueError, "overrides shared"):
                version.check(self.data, root)


if __name__ == "__main__":
    unittest.main()
