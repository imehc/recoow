import hashlib
import os
from pathlib import Path
import shutil
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
        self.openapi = self.bundle / "openapi"
        self.openapi.mkdir()
        (self.bundle / "build-info.json").write_text('{"version":"0.0.1","commit":"fixture","dirty":false}')
        (self.openapi / "openapi.yaml").write_text("openapi: 3.0.3\n")
        for architecture in ("amd64", "arm64"):
            binary = self.bundle / f"recoow-api-linux-{architecture}"
            binary.write_text('#!/usr/bin/env bash\nprintf "%s|%s|%s" "$RECOOW_DATA" "$RECOOW_OPENAPI" "$RECOOW_LISTEN"\n')
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
        self.environment["RECOOW_BACKUP_DATABASE_URL"] = "postgres://backup:backup-only-password@localhost/recoow"
        self.command("pg_dump", 'database=\nfor argument in "$@"; do case "$argument" in --dbname=*) database=${argument#--dbname=} ;; --file=*) output=${argument#--file=} ;; esac; done\n[ "$database" = "$RECOOW_BACKUP_DATABASE_URL" ] || exit 71\nprintf "postgres-dump-fixture" > "$output"')
        (self.data / "media" / "account").mkdir(parents=True)
        (self.data / "media" / "account" / "checksum").write_bytes(b"original-media")
        (self.bundle / "recoow.env").write_text(
            f"RECOOW_DATA={self.data}\nRECOOW_OPENAPI={self.openapi / 'openapi.yaml'}\nRECOOW_DATABASE_URL=postgres://runtime:runtime-password@localhost/recoow\n"
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
        self.assertEqual(result.stdout.decode(), f"{self.data}|{self.openapi / 'openapi.yaml'}|127.0.0.1:8999")

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
            for required in ("database.dump", "data/media/account/checksum", "openapi/openapi.yaml",
                             "recoow-api-linux-amd64", "recoow-api-linux-arm64", "install.sh", "SHA256SUMS"):
                self.assertIn(f"./{required}", names)
            configuration = archive.extractfile("./recoow.env").read().decode()
            self.assertIn("RECOOW_DATA=\n", configuration)
            self.assertIn("RECOOW_MAX_ACCOUNTS=3\n", configuration)
            self.assertIn("RECOOW_TLS_KEY=\n", configuration)
            self.assertNotIn("/old/private.pem", configuration)
            self.assertNotIn("backup-only-password", configuration)
            self.assertNotIn("runtime-password", configuration)
            self.assertEqual(archive.extractfile("./build-info.json").read(), (self.bundle / "build-info.json").read_bytes())

    def test_export_excludes_metadata_and_obsolete_sqlite_files(self):
        (self.data / "._recoow.db").write_bytes(b"AppleDouble")
        (self.openapi / "._openapi.yaml").write_bytes(b"\x00\x05\x16\x07")
        (self.data / "recoow.db-wal").write_bytes(b"wal-fixture")
        self.manifest()
        output = self.directory / "migration.tar.gz"
        self.export(output)
        with tarfile.open(output) as archive:
            self.assertFalse(any(Path(name).name.startswith("._") for name in archive.getnames()))
            self.assertNotIn("./data/recoow.db-wal", archive.getnames())
            self.assertEqual((self.data / "recoow.db-wal").read_bytes(), b"wal-fixture")

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
        (restored / "data/media/account/checksum").write_bytes(b"new-media")
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
            self.assertIn("./database.dump", archive.getnames())

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

    def test_pg_dump_failure_does_not_publish_archive(self):
        self.command("pg_dump", "exit 71")
        output = self.directory / "failed-backup.tar.gz"
        self.export(output, success=False)
        self.assertFalse(output.exists())
        self.assertEqual((self.data / "media/account/checksum").read_bytes(), b"original-media")


if __name__ == "__main__":
    unittest.main()
