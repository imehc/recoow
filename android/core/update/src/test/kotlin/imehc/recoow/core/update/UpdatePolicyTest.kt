package imehc.recoow.core.update

import imehc.recoow.core.domain.update.*
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdatePolicyTest {
    private val release = AppRelease("0.6.1", 7, "https://github.com/imehc/recoow/releases/download/android-v0.6.1/recoow-0.6.1-arm64.apk",
        "ab".repeat(32), 1024, 26, "Release notes")

    @Test fun untrustedUrlsAndUnboundedDownloadsAreRejected() {
        UpdatePolicy.validate(release)
        val invalid = listOf(
            release.copy(downloadUrl = release.downloadUrl.replace("https:", "http:")),
            release.copy(downloadUrl = release.downloadUrl.replace("github.com", "github.com.attacker.example")),
            release.copy(downloadUrl = release.downloadUrl.replace("imehc/recoow", "other/recoow")),
            release.copy(downloadUrl = "https://github.com/imehc/recoow/releases/download/%2e%2e/fake.apk"),
            release.copy(downloadUrl = release.downloadUrl + "?redirect=other"),
            release.copy(sizeBytes = 0), release.copy(sizeBytes = UpdatePolicy.MAX_APK_BYTES + 1),
            release.copy(sha256 = "abc"), release.copy(versionCode = 0),
        )
        invalid.forEach { assertThrows(AppUpdateException::class.java) { UpdatePolicy.validate(it) } }
    }

    @Test fun installationRequiresNewVersionAndTheExactInstalledSigningIdentity() {
        fun verify(packageName: String = "imehc.recoow", version: Long = 7,
            signers: Set<String> = setOf("release-key"), minSdk: Int = 26) =
            UpdatePolicy.validatePackage(release, packageName, "imehc.recoow", version, 6, minSdk, setOf("release-key"), signers)
        verify()
        listOf<() -> Unit>(
            { verify(packageName = "other.app") }, { verify(version = 6) }, { verify(version = 8) },
            { verify(signers = emptySet()) }, { verify(signers = setOf("attacker-key")) },
            { verify(signers = setOf("release-key", "extra-key")) }, { verify(minSdk = 28) },
        ).forEach { assertThrows(AppUpdateException::class.java) { it() } }
    }
}
