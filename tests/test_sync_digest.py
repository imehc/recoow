from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(sys.platform == "darwin" and shutil.which("xcrun"), "requires the macOS Swift compiler")
class SwiftSyncDigestTests(unittest.TestCase):
    def test_shared_exact_number_and_unicode_vectors(self):
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "sync-digest-checks"
            result = subprocess.run([
                "xcrun", "swiftc", "-module-cache-path", str(Path(directory) / "modules"),
                "-o", str(binary), str(root / "ios/recoow/Core/Sync/SyncSnapshotDigest.swift"),
                str(root / "tests/fixtures/SyncDigestChecks.swift"),
            ], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run([str(binary), str(root / "tests/fixtures/sync-digest-v1.json")], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
