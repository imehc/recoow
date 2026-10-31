#!/usr/bin/env python3
"""Inspect the packaged APK. A build check is deliberately separate from signing/upgrade checks."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = '{http://schemas.android.com/apk/res/android}'
PACKAGE = 'imehc.recoow'
PERMISSIONS = {
    'INTERNET', 'ACCESS_NETWORK_STATE', 'ACCESS_WIFI_STATE', 'WAKE_LOCK',
    'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'FOREGROUND_SERVICE',
    'FOREGROUND_SERVICE_LOCATION', 'POST_NOTIFICATIONS', 'SCHEDULE_EXACT_ALARM',
    'RECEIVE_BOOT_COMPLETED', 'RECORD_AUDIO', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE',
}
LEGACY_STORAGE = {'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE'}
SYSTEM_EXPORTS = {
    'androidx.work.impl.background.systemjob.SystemJobService': 'android.permission.BIND_JOB_SERVICE',
    'androidx.work.impl.diagnostics.DiagnosticsReceiver': 'android.permission.DUMP',
    'androidx.profileinstaller.ProfileInstallReceiver': 'android.permission.DUMP',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*command):
    result = subprocess.run([str(x) for x in command], capture_output=True, text=True)
    require(result.returncode == 0, '{} failed: {}'.format(Path(command[0]).name, (result.stderr + result.stdout)[-2500:]))
    return result.stdout


def inspect_manifest(xml, min_sdk, target_sdk):
    root = ET.fromstring(xml)
    require(root.get('package') == PACKAGE, 'Expected production package ' + PACKAGE)
    version = int(root.get(ANDROID + 'versionCode', '0'))
    version_name = root.get(ANDROID + 'versionName', '')
    require(version > 0 and bool(version_name.strip()), 'Missing release version')
    sdk = root.find('uses-sdk')
    require(sdk is not None, 'Missing uses-sdk')
    require(sdk.get(ANDROID + 'minSdkVersion') == str(min_sdk), 'Unexpected minimum SDK')
    require(sdk.get(ANDROID + 'targetSdkVersion') == str(target_sdk), 'Unexpected target SDK')
    app = root.find('application')
    require(app is not None, 'Missing application')
    for flag in ('debuggable', 'testOnly', 'usesCleartextTraffic'):
        require(app.get(ANDROID + flag, 'false') == 'false', 'Release enables ' + flag)
    require(app.get(ANDROID + 'allowBackup') == 'false', 'System backup must remain disabled')
    require(root.find('instrumentation') is None, 'Instrumentation is present in release')
    permissions = []
    for node in root:
        if not node.tag.startswith('uses-permission'):
            continue
        name = node.get(ANDROID + 'name', '')
        short = name.removeprefix('android.permission.')
        require(name == PACKAGE + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION' or
                name == 'android.permission.' + short and short in PERMISSIONS,
                'Unreviewed release permission: ' + name)
        if short in LEGACY_STORAGE:
            require(node.get(ANDROID + 'maxSdkVersion') == '28', 'Legacy storage permission must stop at API28: ' + name)
        permissions.append({'name': name, 'maxSdk': node.get(ANDROID + 'maxSdkVersion')})
    for node in app:
        name = node.get(ANDROID + 'name', '')
        require('BenchmarkFixture' not in name and '.performance.' not in name,
                'Performance fixture is present in release')
        if node.tag not in ('activity', 'activity-alias', 'provider', 'receiver', 'service'):
            continue
        exported = node.get(ANDROID + 'exported')
        require(exported in ('true', 'false'), 'Component needs explicit exported state: ' + name)
        if exported == 'true':
            if name not in (PACKAGE + '.MainActivity', PACKAGE + '.widget.QuickToolWidgetConfiguration'):
                permission = SYSTEM_EXPORTS.get(name)
                require(permission is not None and node.get(ANDROID + 'permission') == permission,
                        'Unreviewed exported component: ' + name)
    required_features = [dict(n.attrib) for n in root.findall('uses-feature')
                         if n.get(ANDROID + 'required', 'true') == 'true']
    require(not any(n.get(ANDROID + 'name', '').startswith('android.hardware.vulkan') for n in required_features),
            'Vulkan must be optional; devices without Vulkan use the OpenGL map backend')
    return {'package': PACKAGE, 'versionCode': version, 'versionName': version_name,
            'minSdk': min_sdk, 'targetSdk': target_sdk, 'permissions': permissions,
            'requiredFeatures': required_features}


def elf_alignment(data, abi):
    require(data[:4] == b'\x7fELF' and len(data) >= 64, 'Invalid ELF file')
    expected_class = 2 if abi in ('arm64-v8a', 'x86_64') else 1
    require(abi in ('arm64-v8a', 'x86_64', 'armeabi-v7a', 'x86') and data[4] == expected_class,
            'ELF class does not match ABI ' + abi)
    require(data[5] in (1, 2), 'Invalid ELF endianness')
    endian = '<' if data[5] == 1 else '>'
    is64 = expected_class == 2
    offset = struct.unpack_from(endian + ('Q' if is64 else 'I'), data, 32 if is64 else 28)[0]
    size, count = struct.unpack_from(endian + 'HH', data, 54 if is64 else 42)
    require(count > 0 and size >= (56 if is64 else 32) and offset + size * count <= len(data),
            'Invalid ELF program headers')
    aligns = []
    for i in range(count):
        fields = struct.unpack_from(endian + ('IIQQQQQQ' if is64 else 'IIIIIIII'), data, offset + i * size)
        if fields[0] != 1:  # PT_LOAD
            continue
        file_offset, address, file_size, alignment = (fields[2], fields[3], fields[5], fields[7]) if is64 else (fields[1], fields[2], fields[4], fields[7])
        page = 16384 if is64 else 4096
        require(alignment >= page and alignment & (alignment - 1) == 0 and
                file_offset % page == address % page, 'PT_LOAD does not support {} byte pages'.format(page))
        require(file_offset + file_size <= len(data), 'ELF load segment exceeds file')
        aligns.append(alignment)
    require(aligns, 'ELF has no load segments')
    return min(aligns)


def inspect_zip(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        for profile in ('assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'):
            require(profile in names and archive.getinfo(profile).file_size > 0, 'Missing packaged profile: ' + profile)
        native = []
        for info in archive.infolist():
            if not info.filename.startswith('lib/') or not info.filename.endswith('.so'):
                continue
            require(info.compress_type == zipfile.ZIP_STORED, 'Native library is compressed: ' + info.filename)
            abi = info.filename.split('/')[1]
            native.append({'path': info.filename, 'loadAlignment': elf_alignment(archive.read(info), abi)})
        require(any(n['path'].startswith('lib/arm64-v8a/') for n in native), 'Missing ARM64 native libraries')
        return native


def certificate_digest(value):
    digest = value.replace(':', '').lower()
    require(re.fullmatch('[0-9a-f]{64}', digest) is not None, 'Certificate SHA-256 must contain 64 hex digits')
    return digest


def check_certificate(output, expected):
    require(re.search(r'CN\s*=\s*Android Debug(?:[,\s]|$)', output, re.I) is None,
            'Android debug signing certificate is not a distribution identity')
    # Build Tools 37 labels the v3 signer as "V3.0 Signer:"; older tools use "Signer #1".
    digests = re.findall(r'^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest: ([0-9a-fA-F:]+)$', output, re.M)
    require(len(digests) == 1 and certificate_digest(digests[0]) == expected, 'Signing certificate does not match the pinned identity')
    return expected


def check_upgrade(previous, current):
    require(previous['package'] == current['package'], 'Upgrade package changed')
    require(current['versionCode'] > previous['versionCode'], 'Upgrade versionCode must increase')


def apk_from_directory(directory):
    metadata = json.loads((directory / 'output-metadata.json').read_text())
    elements = metadata['elements']
    require(len(elements) == 1 and not elements[0].get('filters'), 'Expected one universal APK')
    apk = (directory / elements[0]['outputFile']).resolve()
    require(apk.parent == directory.resolve() and apk.suffix == '.apk', 'Unexpected APK output path')
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--apk', type=Path)
    source.add_argument('--apk-dir', type=Path)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--build-tools', required=True)
    parser.add_argument('--min-sdk', type=int, required=True)
    parser.add_argument('--target-sdk', type=int, required=True)
    parser.add_argument('--certificate-sha256', help='Enable signing checks against this independently recorded certificate')
    parser.add_argument('--previous-apk', type=Path, help='Also require the same signing identity and a higher versionCode')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    if args.previous_apk and not args.certificate_sha256:
        parser.error('--previous-apk requires --certificate-sha256')
    report = {'mode': 'signed-apk' if args.certificate_sha256 else 'build-only', 'passed': False}
    try:
        apk = args.apk or apk_from_directory(args.apk_dir)
        analyzer = args.sdk / 'cmdline-tools/latest/bin/apkanalyzer'
        build_tools = args.sdk / 'build-tools' / args.build_tools
        report.update(inspect_manifest(run(analyzer, 'manifest', 'print', apk), args.min_sdk, args.target_sdk))
        report['nativeLibraries'] = inspect_zip(apk)
        run(build_tools / 'zipalign', '-c', '-P', '16', '-v', '4', apk)
        report['sha256'] = hashlib.sha256(apk.read_bytes()).hexdigest()
        if args.certificate_sha256:
            expected = certificate_digest(args.certificate_sha256)
            signer = build_tools / 'apksigner'
            report['certificateSha256'] = check_certificate(run(signer, 'verify', '--verbose', '--print-certs', apk), expected)
            if args.previous_apk:
                check_certificate(run(signer, 'verify', '--verbose', '--print-certs', args.previous_apk), expected)
                previous_xml = ET.fromstring(run(analyzer, 'manifest', 'print', args.previous_apk))
                previous = {'package': previous_xml.get('package'), 'versionCode': int(previous_xml.get(ANDROID + 'versionCode', '0'))}
                check_upgrade(previous, report)
                report['upgradesVersionCode'] = previous['versionCode']
        report['passed'] = True
    except (ValueError, OSError, KeyError, ET.ParseError, zipfile.BadZipFile, struct.error) as error:
        report['error'] = str(error)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
