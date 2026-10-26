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
import urllib.request
import uuid


@unittest.skipUnless(os.environ.get("RECOOW_DOCKER_E2E") == "1", "requires opt-in Docker end-to-end environment")
class DeploymentEndToEndTests(unittest.TestCase):
    def test_docker_export_runs_without_docker_with_original_account_and_media(self):
        package_path = Path(os.environ["RECOOW_TEST_PACKAGE"]).resolve()
        temporary = tempfile.TemporaryDirectory(prefix="recoow-deployment-e2e-")
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        bundle = directory / "bundle"
        bundle.mkdir()
        with tarfile.open(package_path) as archive:
            self.assertFalse(any(Path(name).name.startswith("._") for name in archive.getnames()))
            archive.extractall(bundle)
        identifier = uuid.uuid4().hex[:12]
        application_image = f"recoow-e2e-app:{identifier}"
        helper_image = f"recoow-e2e-helper:{identifier}"
        container = f"recoow-e2e-original-{identifier}"
        restored_container = f"recoow-e2e-restored-{identifier}"
        self.addCleanup(self.docker, "image", "rm", application_image, helper_image, check=False)
        self.addCleanup(self.docker, "rm", "-fv", container, restored_container, check=False)
        build_options = []
        if os.environ.get("RECOOW_TEST_ALPINE_IP"):
            build_options = ["--add-host", f"dl-cdn.alpinelinux.org:{os.environ['RECOOW_TEST_ALPINE_IP']}"]
        self.docker("build", *build_options, "-t", application_image, str(bundle))
        helper_context = directory / "helper"
        helper_context.mkdir()
        (helper_context / "Dockerfile").write_text(
            "FROM alpine:3.24\n"
            "RUN apk add --no-cache bash coreutils util-linux curl docker-cli python3\n"
        )
        self.docker("build", *build_options, "-t", helper_image, str(helper_context))
        self.docker("run", "-d", "--name", container, "-p", "127.0.0.1::8080", application_image)
        original_url = self.container_url(container)
        self.wait_ready(original_url)
        account = self.request(original_url, "POST", "/api/account/init", {})
        token = account["deviceToken"]
        media = b"recoow-deployment-migration-media"
        checksum = hashlib.sha256(media).hexdigest()
        self.request(original_url, "PUT", f"/api/media/objects/{checksum}", media, token)
        before = self.request(original_url, "GET", "/api/sync/capabilities", token=token)
        self.docker(
            "run", "--rm", "-v", "/var/run/docker.sock:/var/run/docker.sock",
            "-v", f"{directory}:/qa", helper_image,
            "bash", "/qa/bundle/export.sh", "--mode", "docker", "--bundle", "/qa/bundle",
            "--container", container, "--output", "/qa/migration.tar.gz",
        )
        original_url = self.container_url(container)
        self.wait_ready(original_url)
        restored = directory / "restored"
        restored.mkdir()
        with tarfile.open(directory / "migration.tar.gz") as archive:
            archive.extractall(restored)
        self.docker("run", "--rm", "-v", f"{restored}:/runtime", helper_image,
                    "bash", "/runtime/run.sh", "--verify")
        self.docker("run", "-d", "--name", restored_container, "-p", "127.0.0.1::8080",
                    "-v", f"{restored}:/runtime", helper_image, "bash", "/runtime/run.sh")
        restored_url = self.container_url(restored_container)
        self.wait_ready(restored_url)
        after = self.request(restored_url, "GET", "/api/sync/capabilities", token=token)
        self.assertEqual(before, after)
        self.assertEqual(self.request(restored_url, "GET", f"/api/media/objects/{checksum}", token=token), media)
        self.assertEqual(self.docker("inspect", "--format", "{{.Config.User}}", container).strip(), "recoow")
        deadline = time.monotonic() + 70
        while time.monotonic() < deadline:
            health = self.docker("inspect", "--format", "{{.State.Health.Status}}", container).strip()
            if health == "healthy":
                break
            time.sleep(1)
        self.assertEqual(health, "healthy")

    def docker(self, *arguments, check=True):
        result = subprocess.run(["docker", *arguments], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=360)
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
                self.request(base_url, "GET", "/api/schema")
            except urllib.error.HTTPError as error:
                if error.code == 401:
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.2)
        self.fail(f"backend did not become ready: {base_url}")


if __name__ == "__main__":
    unittest.main()
