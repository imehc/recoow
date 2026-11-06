import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid


@unittest.skipUnless(os.environ.get("RECOOW_DOCKER_E2E") == "1", "requires opt-in Docker end-to-end environment")
class DeploymentEndToEndTests(unittest.TestCase):
    def test_postgres_export_restores_account_login_and_media(self):
        package_path = Path(os.environ["RECOOW_TEST_PACKAGE"]).resolve()
        temporary = tempfile.TemporaryDirectory(prefix="recoow-deployment-e2e-")
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        bundle = directory / "bundle"
        bundle.mkdir()
        with tarfile.open(package_path) as archive:
            archive.extractall(bundle, filter="data") if hasattr(tarfile, "data_filter") else archive.extractall(bundle)
        identifier = uuid.uuid4().hex[:12]
        network = f"recoow-e2e-{identifier}"
        database = f"recoow-e2e-db-{identifier}"
        application_image = f"recoow-e2e-app:{identifier}"
        helper_image = f"recoow-e2e-helper:{identifier}"
        container = f"recoow-e2e-original-{identifier}"
        restored_container = f"recoow-e2e-restored-{identifier}"
        postgres_image = os.environ.get("RECOOW_TEST_POSTGRES_IMAGE", "postgres:17-alpine")
        self.docker("network", "create", network)
        self.addCleanup(self.docker, "network", "rm", network, check=False)
        self.addCleanup(self.docker, "image", "rm", application_image, helper_image, check=False)
        self.addCleanup(self.docker, "rm", "-fv", container, restored_container, database, check=False)
        password = uuid.uuid4().hex
        self.docker("run", "-d", "--name", database, "--network", network,
                    "-e", "POSTGRES_USER=recoow_admin", "-e", f"POSTGRES_PASSWORD={password}",
                    "-e", "POSTGRES_DB=recoow", postgres_image)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            # 初始化脚本会启动再停止仅监听 socket 的临时实例；等待最终 TCP 实例。
            result = subprocess.run(["docker", "exec", database, "pg_isready", "-h", "127.0.0.1",
                                     "-U", "recoow_admin", "-d", "recoow"], capture_output=True)
            if result.returncode == 0:
                break
            time.sleep(0.2)
        else:
            self.fail("PostgreSQL did not become ready")
        self.docker("exec", database, "psql", "-U", "recoow_admin", "-d", "recoow", "-v", "ON_ERROR_STOP=1", "-c",
                    f"CREATE ROLE recoow_app LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '{password}';")
        self.docker("build", "-t", application_image, "--build-arg",
                    "BASE_IMAGE=" + os.environ.get("RECOOW_TEST_BASE_IMAGE", "alpine:3.24"), str(bundle))
        helper_context = directory / "helper"
        helper_context.mkdir()
        (helper_context / "Dockerfile").write_text(
            f"FROM {postgres_image}\n"
            "RUN apk add --no-cache bash coreutils util-linux curl docker-cli && mkdir -p /run/lock\n"
        )
        self.docker("build", "-t", helper_image, str(helper_context))
        runtime_url = f"postgres://recoow_app:{password}@{database}:5432/recoow?sslmode=disable"
        owner_url = f"postgres://recoow_admin:{password}@{database}:5432/recoow?sslmode=disable"
        self.docker("run", "--rm", "--network", network, "-e", f"RECOOW_DATABASE_URL={runtime_url}",
                    "-e", f"RECOOW_MIGRATION_DATABASE_URL={owner_url}", application_image, "-migrate")
        self.docker("run", "-d", "--name", container, "--network", network, "-p", "127.0.0.1::8080",
                    "-e", f"RECOOW_DATABASE_URL={runtime_url}", application_image)
        original_url = self.container_url(container)
        self.wait_ready(original_url)
        account = self.request(original_url, "POST", "/api/accounts", {})
        token = account["device_token"]
        credentials = {"username": "e2e-owner", "password": "e2e-owner-long-password"}
        self.request(original_url, "POST", "/api/users/me/credentials", credentials, token)
        bill = {"id": "e2e-bill", "title": "备份前的账单", "original_amount_cents": 2500,
                "discount_amount_cents": 0, "final_amount_cents": 2500, "transaction_type": "expense",
                "category": "food", "payment_method": "cash", "occurred_at": 1000,
                "settlement_status": "active", "future_field": {"integer": 9007199254740993}}
        mutation = {"mutation_id": "e2e-bill-create", "base_version": 0, "data_version": 3,
                    "updated_at_ms": 1000, "snapshot": bill}
        created = self.request(original_url, "POST", "/api/bills", mutation, token)
        self.assertEqual(created["status"], "applied")
        original_bill = self.request(original_url, "GET", "/api/bills/e2e-bill", token=token)
        media = b"recoow-deployment-migration-media"
        checksum = hashlib.sha256(media).hexdigest()
        self.request(original_url, "PUT", f"/api/media/objects/{checksum}", media, token)
        before = self.request(original_url, "GET", "/api/sync/capabilities", token=token)
        self.docker("run", "--rm", "--network", network,
                    "-v", "/var/run/docker.sock:/var/run/docker.sock", "-v", f"{directory}:/qa",
                    "-e", f"RECOOW_BACKUP_DATABASE_URL={owner_url}", helper_image,
                    "bash", "/qa/bundle/export.sh", "--mode", "docker", "--bundle", "/qa/bundle",
                    "--container", container, "--output", "/qa/migration.tar.gz")
        self.wait_ready(self.container_url(container))
        restored = directory / "restored"
        restored.mkdir()
        with tarfile.open(directory / "migration.tar.gz") as archive:
            archive.extractall(restored, filter="data") if hasattr(tarfile, "data_filter") else archive.extractall(restored)
        self.assertNotIn(password, (restored / "recoow.env").read_text())
        self.docker("exec", database, "createdb", "-U", "recoow_admin", "recoow_restored")
        self.docker("exec", "-i", database, "pg_restore", "-U", "recoow_admin", "-d", "recoow_restored",
                    "--exit-on-error", "--single-transaction", "--no-owner", "--no-privileges",
                    input=(restored / "database.dump").read_bytes())
        runtime_url = runtime_url.replace("/recoow?", "/recoow_restored?")
        owner_url = owner_url.replace("/recoow?", "/recoow_restored?")
        self.docker("run", "--rm", "--network", network, "-e", f"RECOOW_DATABASE_URL={runtime_url}",
                    "-e", f"RECOOW_MIGRATION_DATABASE_URL={owner_url}", application_image, "-migrate")
        self.docker("run", "--rm", "-v", f"{restored}:/runtime", helper_image,
                    "bash", "-c", "chown -R 10001:10001 /runtime/data")
        self.docker("run", "-d", "--name", restored_container, "--network", network, "-p", "127.0.0.1::8080",
                    "-e", f"RECOOW_DATABASE_URL={runtime_url}", "-v", f"{restored / 'data'}:/data", application_image)
        restored_url = self.container_url(restored_container)
        self.wait_ready(restored_url)
        self.assertEqual(before, self.request(restored_url, "GET", "/api/sync/capabilities", token=token))
        self.assertEqual(media, self.request(restored_url, "GET", f"/api/media/objects/{checksum}", token=token))
        login = self.request(restored_url, "POST", "/api/sessions", credentials)
        self.assertEqual(login["account_id"], account["account_id"])
        session = login["access_token"]
        self.assertEqual(original_bill, self.request(restored_url, "GET", "/api/bills/e2e-bill", token=session))
        # 回执、结构化账单和未知字段必须一起恢复；原 mutation 重试不能创建新版本。
        retry = self.request(restored_url, "POST", "/api/bills", mutation, token)
        self.assertEqual(retry["version"], created["version"])
        update = dict(mutation, mutation_id="e2e-bill-update", base_version=created["version"], updated_at_ms=2000,
                      snapshot=dict(original_bill["snapshot"], title="恢复后的账单", original_amount_cents=1900, final_amount_cents=1900))
        updated = self.request(restored_url, "PUT", "/api/bills/e2e-bill", update, session)
        self.assertEqual(updated["status"], "applied")
        with self.assertRaises(urllib.error.HTTPError) as conflict:
            self.request(restored_url, "PUT", "/api/bills/e2e-bill",
                         dict(update, mutation_id="e2e-stale", snapshot=dict(update["snapshot"], title="过期修改")), session)
        self.assertEqual(conflict.exception.code, 409)
        page = self.request(restored_url, "GET", "/api/bills?q=" + urllib.parse.quote("恢复后"), token=token)
        self.assertEqual(len(page["rows"]), 1)
        self.assertEqual(page["rows"][0]["snapshot"]["future_field"], bill["future_field"])
        self.assertEqual(self.request(restored_url, "GET", "/api/statistics", token=session)["bill_expense_cents"], 1900)
        deleted = self.request(restored_url, "DELETE", "/api/bills/e2e-bill",
                               {"mutation_id": "e2e-bill-delete", "base_version": updated["version"],
                                "data_version": 3, "updated_at_ms": 3000}, session)
        self.assertEqual(deleted["status"], "applied")
        self.assertEqual(self.request(restored_url, "GET", "/api/bills", token=token)["rows"], [])
        self.assertEqual(self.request(restored_url, "GET", "/api/statistics", token=session)["bill_expense_cents"], 0)
        changes = self.request(restored_url, "GET", "/api/sync/changes?cursor=" + str(before["dataset"]["head"]), token=token)
        self.assertEqual([entry["operation"] for entry in changes["entries"]], ["update", "delete"])
        self.assertEqual(self.docker("inspect", "--format", "{{.Config.User}}", container).strip(), "recoow")

    def docker(self, *arguments, check=True, input=None):
        result = subprocess.run(["docker", *arguments], input=input, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=360)
        if check:
            self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
        return result.stdout.decode()

    def container_url(self, container):
        mapping = self.docker("port", container, "8080/tcp").strip()
        return f"http://{mapping}"

    def request(self, base_url, method, path, body=None, token=None):
        headers = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        elif body is not None:
            headers["Content-Type"] = "application/octet-stream"
        request = urllib.request.Request(base_url + path, data=body, headers=headers, method=method)
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=10) as response:
            result = response.read()
            if "application/json" in response.headers.get("Content-Type", ""):
                return json.loads(result)
            return result

    def wait_ready(self, base_url):
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            try:
                self.request(base_url, "GET", "/api/sync/capabilities")
            except urllib.error.HTTPError as error:
                if error.code == 401:
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.2)
        self.fail(f"backend did not become ready: {base_url}")


if __name__ == "__main__":
    unittest.main()
