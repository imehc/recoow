import hashlib
from contextlib import closing
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.bundle = self.directory / "bundle"
        self.bundle.mkdir()
        for source in (ROOT / "deploy").iterdir():
            if source.is_file():
                shutil.copy2(source, self.bundle / source.name)
        self.schema = self.bundle / "schema"
        self.schema.mkdir()
        (self.bundle / "build-info.json").write_text('{"version":"0.0.1","commit":"fixture","dirty":false}')
        (self.schema / "bills.json").write_text('{"table":"bills"}')
        for architecture in ("amd64", "arm64"):
            binary = self.bundle / f"recoow-api-linux-{architecture}"
            binary.write_text('#!/usr/bin/env bash\nprintf "%s|%s|%s" "$RECOOW_DATA" "$RECOOW_SCHEMA" "$RECOOW_LISTEN"\n')
            binary.chmod(0o755)
        self.commands = self.directory / "commands"
        self.commands.mkdir()
        self.environment = {key: value for key, value in os.environ.items() if not key.startswith("RECOOW_")}
        self.environment["PATH"] = f"{self.commands}:{os.environ['PATH']}"
        if not shutil.which("sha256sum"):
            self.command("sha256sum", 'exec shasum -a 256 "$@"')
        if not shutil.which("flock"):
            self.command("flock", "exit 0")
        self.command("uname", 'case "$1" in -s) echo Linux ;; -m) echo x86_64 ;; esac')
        self.data = self.directory / "data"
        self.data.mkdir()
        with closing(sqlite3.connect(self.data / "recoow.db")) as database, database:
            database.execute("CREATE TABLE account (id TEXT PRIMARY KEY)")
            database.execute("INSERT INTO account VALUES ('original-account')")
        (self.data / "media" / "account").mkdir(parents=True)
        (self.data / "media" / "account" / "checksum").write_bytes(b"original-media")
        (self.bundle / "recoow.env").write_text(
            f"RECOOW_DATA={self.data}\nRECOOW_SCHEMA={self.schema}\n"
            "RECOOW_LISTEN=127.0.0.1:8091\nRECOOW_MAX_ACCOUNTS=3\n"
            "RECOOW_TLS_CERT=/old/cert.pem\nRECOOW_TLS_KEY=/old/private.pem\n"
        )
        self.manifest()

    def command(self, name, body):
        path = self.commands / name
        path.write_text(f"#!/usr/bin/env bash\n{body}\n")
        path.chmod(0o755)

    def manifest(self):
        entries = []
        for path in sorted(self.bundle.rglob("*")):
            if path.is_file() and path.name != "SHA256SUMS":
                checksum = hashlib.sha256(path.read_bytes()).hexdigest()
                entries.append(f"{checksum}  ./{path.relative_to(self.bundle)}\n")
        (self.bundle / "SHA256SUMS").write_text("".join(entries))

    def run_script(self, name, *arguments, success=True, environment=None):
        result = subprocess.run(
            ["bash", str(self.bundle / name), *map(str, arguments)],
            env=environment or self.environment, capture_output=True, timeout=30,
        )
        if success:
            self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
        else:
            self.assertNotEqual(result.returncode, 0)
        return result

    def export(self, output, *extra, success=True):
        return self.run_script("export.sh", "--mode", "stopped", "--data", self.data,
                               "--output", output, *extra, success=success)

    def test_run_selects_linux_binary_and_environment_overrides(self):
        environment = dict(self.environment, RECOOW_LISTEN="127.0.0.1:8999")
        result = self.run_script("run.sh", environment=environment)
        self.assertEqual(result.stdout.decode(), f"{self.data}|{self.schema}|127.0.0.1:8999")

    def test_run_rejects_unsupported_os(self):
        self.command("uname", "echo Darwin")
        self.run_script("run.sh", success=False)

    def test_config_is_not_executed_as_shell(self):
        marker = self.directory / "must-not-exist"
        (self.bundle / "recoow.env").write_text(f"RECOOW_BOOTSTRAP_TOKEN=$(touch {marker})\n")
        self.run_script("run.sh")
        self.assertFalse(marker.exists())

    def test_export_contains_database_media_and_both_architectures(self):
        output = self.directory / "migration.tar.gz"
        self.export(output)
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        with tarfile.open(output) as archive:
            names = archive.getnames()
            for required in ("data/recoow.db", "data/media/account/checksum", "schema/bills.json",
                             "recoow-api-linux-amd64", "recoow-api-linux-arm64", "install.sh", "SHA256SUMS"):
                self.assertIn(f"./{required}", names)
            configuration = archive.extractfile("./recoow.env").read().decode()
            self.assertIn("RECOOW_DATA=\n", configuration)
            self.assertIn("RECOOW_MAX_ACCOUNTS=3\n", configuration)
            self.assertIn("RECOOW_TLS_KEY=\n", configuration)
            self.assertNotIn("/old/private.pem", configuration)
            self.assertEqual(archive.extractfile("./build-info.json").read(), (self.bundle / "build-info.json").read_bytes())

    def test_export_excludes_metadata_and_preserves_wal_bytes(self):
        (self.data / "._recoow.db").write_bytes(b"AppleDouble")
        (self.schema / "._bills.json").write_bytes(b"\x00\x05\x16\x07")
        (self.data / "recoow.db-wal").write_bytes(b"wal-fixture")
        self.manifest()
        output = self.directory / "migration.tar.gz"
        self.export(output)
        with tarfile.open(output) as archive:
            self.assertFalse(any(Path(name).name.startswith("._") for name in archive.getnames()))
            self.assertEqual(archive.extractfile("./data/recoow.db-wal").read(), b"wal-fixture")

    def test_export_refuses_existing_output(self):
        output = self.directory / "migration.tar.gz"
        output.write_bytes(b"keep-existing")
        self.export(output, success=False)
        self.assertEqual(output.read_bytes(), b"keep-existing")

    def test_export_refuses_output_inside_data(self):
        self.export(self.data / "migration.tar.gz", success=False)

    def test_export_rejects_modified_binary(self):
        (self.bundle / "recoow-api-linux-amd64").write_text("tampered")
        output = self.directory / "migration.tar.gz"
        self.export(output, success=False)
        self.assertFalse(output.exists())

    def test_export_verifies_snapshot_and_supports_modified_runtime_data(self):
        output = self.directory / "migration.tar.gz"
        self.export(output)
        restored = self.directory / "restored"
        restored.mkdir()
        with tarfile.open(output) as archive:
            archive.extractall(restored)
        original_bundle = self.bundle
        self.bundle = restored
        self.run_script("run.sh", "--verify")
        with closing(sqlite3.connect(restored / "data/recoow.db")) as database, database:
            database.execute("INSERT INTO account VALUES ('second-account')")
        self.run_script("export.sh", "--mode", "stopped", "--data", restored / "data",
                        "--output", self.directory / "second.tar.gz")
        self.bundle = original_bundle

    def test_export_failure_does_not_publish_partial_output(self):
        self.command("tar", "exit 71")
        output = self.directory / "migration.tar.gz"
        self.export(output, success=False)
        self.assertFalse(output.exists())

    def test_export_stdout_is_only_an_archive(self):
        result = self.export("-")
        output = self.directory / "stream.tar.gz"
        output.write_bytes(result.stdout)
        with tarfile.open(output) as archive:
            self.assertIn("./data/recoow.db", archive.getnames())

    @unittest.skipUnless(os.name == "posix" and os.geteuid() == 0 and Path("/run/lock").exists(), "requires isolated Linux root environment")
    def test_systemd_copy_failure_restores_original_service(self):
        events = self.directory / "service-events"
        self.command("systemctl", f'echo "$*" >> "{events}"\ncase "$*" in *ActiveState*) echo active ;; esac')
        self.command("tar", "exit 71")
        environment = dict(self.environment, RECOOW_ENV_FILE=str(self.bundle / "recoow.env"))
        output = self.directory / "migration.tar.gz"
        self.run_script("export.sh", "--mode", "systemd", "--output", output,
                        environment=environment, success=False)
        self.assertIn("stop recoow.service", events.read_text())
        self.assertIn("start recoow.service", events.read_text())
        self.assertFalse(output.exists())

    @unittest.skipUnless(os.environ.get("RECOOW_DEPLOYMENT_SANDBOX") == "1", "only runs in disposable deployment container")
    def test_installer_preserves_existing_data_and_restores_only_empty_target(self):
        Path("/run/systemd/system").mkdir(parents=True, exist_ok=True)
        self.command("systemctl", "exit 0")
        self.command("curl", "printf 401")
        self.run_script("install.sh")
        target = Path("/var/lib/recoow")
        shutil.copy2(self.data / "recoow.db", target / "recoow.db")
        original_bytes = (target / "recoow.db").read_bytes()
        configuration = Path("/etc/recoow/recoow.env").read_bytes()
        self.run_script("install.sh")
        self.assertEqual((target / "recoow.db").read_bytes(), original_bytes)
        self.assertEqual(Path("/etc/recoow/recoow.env").read_bytes(), configuration)
        read_result = subprocess.run(["su", "-s", "/bin/sh", "recoow", "-c", "test -r /etc/recoow/recoow.env"], capture_output=True)
        self.assertEqual(read_result.returncode, 0, read_result.stderr)
        shutil.copytree(self.data, self.bundle / "data")
        self.manifest()
        self.run_script("install.sh", success=False)
        self.assertEqual((target / "recoow.db").read_bytes(), original_bytes)
        shutil.rmtree(target)
        self.run_script("install.sh")
        with closing(sqlite3.connect(target / "recoow.db")) as database, database:
            self.assertEqual(database.execute("SELECT id FROM account").fetchall(), [("original-account",)])
        self.assertEqual((target / "media/account/checksum").read_bytes(), b"original-media")


if __name__ == "__main__":
    unittest.main()
