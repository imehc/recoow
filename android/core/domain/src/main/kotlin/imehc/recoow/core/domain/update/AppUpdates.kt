package imehc.recoow.core.domain.update

data class InstalledAppVersion(val name: String, val code: Long)

data class AppRelease(
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int,
    val notes: String,
)

data class PreparedAppUpdate(val release: AppRelease)

enum class AppUpdateProblem { CONNECTION, INVALID_RELEASE, INCOMPATIBLE, STORAGE, INSTALLER }

class AppUpdateException(val problem: AppUpdateProblem, cause: Throwable? = null) : Exception(problem.name, cause)

interface AppUpdates {
    val installed: InstalledAppVersion
    suspend fun check(): AppRelease?
    suspend fun download(release: AppRelease, progress: (Long, Long) -> Unit): PreparedAppUpdate
    /** Revalidates the private APK before granting the system installer read access. */
    suspend fun installationUri(update: PreparedAppUpdate): String
}
