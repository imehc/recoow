#!/usr/bin/env python3
"""Publish a verified, immutable Android release with an atomic draft-to-public transition."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import version

REPOSITORY = "imehc/recoow"


def gh(*args):
    result = subprocess.run(["gh", *map(str, args)], capture_output=True, text=True)
    if result.returncode:
        raise ValueError("GitHub operation failed: " + result.stderr[-1500:])
    return result.stdout


def releases():
    pages = json.loads(gh("api", "--paginate", "--slurp", f"repos/{REPOSITORY}/releases?per_page=100"))
    return [entry for page in pages for entry in page]


def check_order(previous, current):
    if previous.get("packageName") != "imehc.recoow" or type(previous.get("versionCode")) is not int:
        raise ValueError("Published Android metadata is invalid")
    if current["versionCode"] <= previous["versionCode"]:
        raise ValueError("versionCode must exceed every published Android release")
    old = previous.get("versionName", "")
    if not version.SEMVER.fullmatch(old) or tuple(map(int, current["versionName"].split('.'))) <= tuple(map(int, old.split('.'))):
        raise ValueError("Android semantic version must exceed every published stable release")


def manifest(apk, report, entry, notes):
    if (report.get("passed") is not True or report.get("mode") != "signed-apk" or
        report.get("package") != "imehc.recoow" or report.get("versionName") != entry["version"] or
        report.get("versionCode") != int(entry["build"]) or
        report.get("certificateSha256") != (ROOT / "android/release-certificate.sha256").read_text().strip()):
        raise ValueError("APK report does not match requested signed release")
    abis = {item["path"].split('/')[1] for item in report["nativeLibraries"]}
    if abis != {"arm64-v8a"}:
        raise ValueError("Release must contain only arm64-v8a")
    size = apk.stat().st_size
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if size not in range(1, 256 * 1024 * 1024 + 1) or digest != report["sha256"] or len(notes) > 16000:
        raise ValueError("APK hash, size or release notes invalid")
    name = f"recoow-{entry['version']}-arm64-v8a.apk"
    return {"schemaVersion": 1, "packageName": "imehc.recoow", "abi": "arm64-v8a",
            "versionName": entry["version"], "versionCode": int(entry["build"]),
            "downloadUrl": f"https://github.com/{REPOSITORY}/releases/download/{entry['tag']}/{name}",
            "sha256": digest, "sizeBytes": size, "minSdk": report["minSdk"], "notes": notes}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--run-number", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--notes", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    try:
        data = version.read()
        version.check(data)
        entry = version.describe(data, "android", args.run_number)
        if args.tag != entry["tag"]:
            raise ValueError("Tag does not match Android version")
        notes = args.notes.read_text()
        report = json.loads(args.report.read_text())
        update = manifest(args.apk, report, entry, notes)
        args.output.mkdir(parents=True, exist_ok=False)
        apk = args.output / update["downloadUrl"].rsplit('/', 1)[-1]
        shutil.copy2(args.apk, apk)
        (args.output / "update.json").write_text(json.dumps(update, ensure_ascii=False, indent=2) + "\n")
        shutil.copy2(args.report, args.output / "release-verification.json")
        (args.output / "SHA256SUMS").write_text("".join(
            f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in sorted(args.output.iterdir()) if p.is_file()))
        if not args.publish:
            return
        all_releases = releases()
        existing = next((r for r in all_releases if r["tag_name"] == entry["tag"]), None)
        if existing and not existing["draft"]:
            raise ValueError("Refusing to replace an already published release")
        with tempfile.TemporaryDirectory() as directory:
            for release in all_releases:
                if release["draft"] or release["prerelease"] or not release["tag_name"].startswith("android/v"):
                    continue
                gh("release", "download", release["tag_name"], "--repo", REPOSITORY, "--pattern", "update.json", "--dir", directory, "--clobber")
                check_order(json.loads((Path(directory) / "update.json").read_text()), update)
        if not existing:
            gh("release", "create", entry["tag"], "--repo", REPOSITORY, "--draft", "--verify-tag",
               "--title", f"Android {entry['version']}", "--notes-file", args.notes)
        # Only draft assets can be replaced when retrying a failed workflow.
        gh("release", "upload", entry["tag"], "--repo", REPOSITORY, "--clobber", *sorted(args.output.iterdir()))
        gh("release", "edit", entry["tag"], "--repo", REPOSITORY, "--draft=false", "--latest", "--notes-file", args.notes)
        print(f"Published {entry['tag']} with versionCode {entry['build']}")
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f"release: {error}\n")


if __name__ == "__main__":
    main()
