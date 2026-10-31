import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

import verify_release as gate


def manifest(extra='', attributes='', components=''):
    return '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="imehc.recoow" android:versionCode="6" android:versionName="0.6.0">
        <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
        {}<application android:allowBackup="false" {}>{}</application></manifest>'''.format(extra, attributes, components)


def elf(is64=True, alignment=16384, address=0):
    data = bytearray(4096)
    data[:6] = b'\x7fELF' + bytes([2 if is64 else 1, 1])
    struct.pack_into('<Q' if is64 else '<I', data, 32 if is64 else 28, 64)
    struct.pack_into('<HH', data, 54 if is64 else 42, 56 if is64 else 32, 1)
    fields = (1, 5, 0, address, address, len(data), len(data), alignment) if is64 else (1, 0, address, address, len(data), len(data), 5, alignment)
    struct.pack_into('<IIQQQQQQ' if is64 else '<IIIIIIII', data, 64, *fields)
    return bytes(data)


class ReleaseGateTest(unittest.TestCase):
    def test_production_identity_and_sdk_are_required(self):
        self.assertEqual(6, gate.inspect_manifest(manifest(), 26, 36)['versionCode'])
        for bad in (manifest().replace('imehc.recoow', 'imehc.recoow.benchmark'),
                    manifest().replace('minSdkVersion="26"', 'minSdkVersion="28"'),
                    manifest().replace('versionCode="6"', 'versionCode="0"')):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                gate.inspect_manifest(bad, 26, 36)

    def test_storage_permission_range_and_new_permissions_fail_closed(self):
        for permission in gate.LEGACY_STORAGE:
            bounded = '<uses-permission android:name="android.permission.{}" android:maxSdkVersion="28"/>'.format(permission)
            gate.inspect_manifest(manifest(bounded), 26, 36)
            with self.assertRaisesRegex(ValueError, 'stop at API28'):
                gate.inspect_manifest(manifest(bounded.replace(' android:maxSdkVersion="28"', '')), 26, 36)
        with self.assertRaisesRegex(ValueError, 'Unreviewed release permission'):
            gate.inspect_manifest(manifest('<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"/>'), 26, 36)

    def test_debug_test_and_cleartext_flags_are_rejected(self):
        for flag in ('debuggable', 'testOnly', 'usesCleartextTraffic'):
            with self.subTest(flag=flag), self.assertRaisesRegex(ValueError, flag):
                gate.inspect_manifest(manifest(attributes='android:{}="true"'.format(flag)), 26, 36)
        with self.assertRaisesRegex(ValueError, 'Instrumentation'):
            gate.inspect_manifest(manifest('<instrumentation android:name="androidx.test.runner.AndroidJUnitRunner"/>'), 26, 36)

    def test_system_export_must_keep_its_signature_permission(self):
        receiver = '<receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" android:exported="true" android:permission="android.permission.DUMP"/>'
        gate.inspect_manifest(manifest(components=receiver), 26, 36)
        for bad in (receiver.replace(' android:permission="android.permission.DUMP"', ''),
                    receiver.replace('android.permission.DUMP', 'android.permission.INTERNET'),
                    receiver.replace('androidx.profileinstaller.ProfileInstallReceiver', 'example.Unreviewed')):
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, 'Unreviewed exported'):
                gate.inspect_manifest(manifest(components=bad), 26, 36)

    def test_performance_fixture_is_rejected_even_when_not_exported(self):
        with self.assertRaisesRegex(ValueError, 'Performance fixture'):
            gate.inspect_manifest(manifest(components='<activity android:name="imehc.recoow.performance.BenchmarkFixtureActivity" android:exported="false"/>'), 26, 36)

    def test_vulkan_cannot_filter_out_opengl_devices(self):
        feature = '<uses-feature android:name="android.hardware.vulkan.version" android:required="false" android:version="0x400003"/>'
        gate.inspect_manifest(manifest(feature), 26, 36)
        with self.assertRaisesRegex(ValueError, 'Vulkan must be optional'):
            gate.inspect_manifest(manifest(feature.replace('required="false"', 'required="true"')), 26, 36)

    def test_64_bit_requires_16k_but_32_bit_retains_4k_compatibility(self):
        self.assertEqual(16384, gate.elf_alignment(elf(), 'arm64-v8a'))
        self.assertEqual(4096, gate.elf_alignment(elf(False, 4096), 'armeabi-v7a'))
        for bad in (elf(alignment=4096), elf(address=4096), elf(alignment=24576)):
            with self.subTest(), self.assertRaisesRegex(ValueError, 'PT_LOAD'):
                gate.elf_alignment(bad, 'arm64-v8a')

    def test_invalid_elf_headers_and_abi_mismatch_are_rejected(self):
        for bad in (b'not ELF', elf()[:65], elf(False)):
            with self.subTest(), self.assertRaises(ValueError):
                gate.elf_alignment(bad, 'arm64-v8a')

    def test_apk_requires_profiles_and_uncompressed_native_libraries(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'app.apk'
            def write(profile=True, compression=zipfile.ZIP_STORED):
                with zipfile.ZipFile(apk, 'w') as archive:
                    if profile:
                        archive.writestr('assets/dexopt/baseline.prof', b'pro\x00')
                        archive.writestr('assets/dexopt/baseline.profm', b'prm\x00')
                    archive.writestr('lib/arm64-v8a/libexample.so', elf(), compress_type=compression)
            write()
            self.assertEqual(1, len(gate.inspect_zip(apk)))
            write(profile=False)
            with self.assertRaisesRegex(ValueError, 'Missing packaged profile'):
                gate.inspect_zip(apk)
            write(compression=zipfile.ZIP_DEFLATED)
            with self.assertRaisesRegex(ValueError, 'compressed'):
                gate.inspect_zip(apk)

    def test_metadata_does_not_select_stale_or_outside_apk(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            metadata = directory / 'output-metadata.json'
            metadata.write_text(json.dumps({'elements': [{'outputFile': 'app-release.apk', 'filters': []}]}))
            self.assertEqual((directory / 'app-release.apk').resolve(), gate.apk_from_directory(directory))
            for elements in ([], [{'outputFile': '../outside.apk'}], [{'outputFile': 'split.apk', 'filters': [{'filterType': 'ABI'}]}]):
                metadata.write_text(json.dumps({'elements': elements}))
                with self.subTest(elements=elements), self.assertRaises(ValueError):
                    gate.apk_from_directory(directory)

    def test_arm64_distribution_rejects_additional_architectures(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/dexopt/baseline.prof', b'pro\x00')
                archive.writestr('assets/dexopt/baseline.profm', b'prm\x00')
                archive.writestr('lib/arm64-v8a/libexample.so', elf())
            gate.inspect_zip(apk, ['arm64-v8a'])
            with zipfile.ZipFile(apk, 'a') as archive:
                archive.writestr('lib/x86_64/libexample.so', elf())
            with self.assertRaisesRegex(ValueError, 'Unexpected APK ABIs'):
                gate.inspect_zip(apk, ['arm64-v8a'])

    def test_certificate_pin_debug_identity_and_multiple_signers(self):
        expected = 'ab' * 32
        output = 'Signer #1 certificate DN: CN=Release\nSigner #1 certificate SHA-256 digest: ' + expected + '\n'
        self.assertEqual(expected, gate.check_certificate(output, expected))
        modern = 'Number of signers: 1\nV3.0 Signer: certificate DN: CN=Release\nV3.0 Signer: certificate SHA-256 digest: ' + expected + '\n'
        self.assertEqual(expected, gate.check_certificate(modern, expected))
        with self.assertRaisesRegex(ValueError, 'pinned identity'):
            gate.check_certificate(modern.replace(expected, 'cd' * 32), expected)
        for bad in (output.replace('CN=Release', 'CN=Android Debug, O=Android'),
                    output.replace(expected, 'cd' * 32), output + output.replace('#1', '#2')):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                gate.check_certificate(bad, expected)

    def test_upgrade_requires_same_package_and_monotonic_version(self):
        previous = {'package': gate.PACKAGE, 'versionCode': 6}
        gate.check_upgrade(previous, dict(previous, versionCode=7))
        for current in (previous, dict(previous, versionCode=5), dict(previous, package='other', versionCode=7)):
            with self.subTest(current=current), self.assertRaises(ValueError):
                gate.check_upgrade(previous, current)


if __name__ == '__main__':
    unittest.main()
