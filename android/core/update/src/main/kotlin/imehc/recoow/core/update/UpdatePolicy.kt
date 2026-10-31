package imehc.recoow.core.update

import imehc.recoow.core.domain.update.AppRelease
import imehc.recoow.core.domain.update.AppUpdateException
import imehc.recoow.core.domain.update.AppUpdateProblem
import java.net.URI

internal object UpdatePolicy {
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val MANIFEST_URL = "https://github.com/imehc/recoow/releases/latest/download/update.json"
    private const val ASSET_PREFIX = "/imehc/recoow/releases/download/"

    fun validate(release: AppRelease) {
        val url = runCatching { URI(release.downloadUrl) }.getOrNull()
        if (release.versionCode !in 1..2_100_000_000L || !release.versionName.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) || release.versionName.length > 80 ||
            release.sizeBytes !in 1..MAX_APK_BYTES || release.minSdk < 26 || release.notes.length > 16_000 ||
            !release.sha256.matches(Regex("[0-9a-f]{64}")) ||
            url?.scheme != "https" || url.host != "github.com" || url.port !in listOf(-1, 443) ||
            url.userInfo != null || url.fragment != null || url.query != null ||
            !url.path.startsWith(ASSET_PREFIX) || !url.path.endsWith(".apk") ||
            url.rawPath != url.path || url.path.split('/').any { it == ".." || it == "." } ||
            url.normalize().path != url.path) {
            throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
        }
    }

    fun validatePackage(release: AppRelease, packageName: String, installedPackage: String,
        versionCode: Long, installedVersion: Long, minSdk: Int,
        installedSigners: Set<String>, downloadedSigners: Set<String>) {
        if (packageName != installedPackage || versionCode != release.versionCode ||
            versionCode <= installedVersion || minSdk != release.minSdk ||
            installedSigners.isEmpty() || installedSigners != downloadedSigners) {
            throw AppUpdateException(AppUpdateProblem.INVALID_RELEASE)
        }
    }
}
