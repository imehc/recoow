package imehc.recoow.core.domain.backup

import java.time.Instant

data class BackupPreview(
    val formatVersion: Int,
    val databaseSchemaVersion: Int,
    val appVersion: String,
    val appBuild: String,
    val exportedAt: Instant,
    val sourceDeviceId: String,
    val metadataJson: String,
    val tableCounts: Map<String, Long>,
    val embeddedMediaBytes: Long,
    val sourcePlatform: String = "iOS",
    val sourceSchemaVersion: Int? = null,
    val sourceAccountScope: String? = null,
    val hasAccountProvenance: Boolean = true,
)

class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

class LegacyBackupException : IllegalArgumentException("Upgrade this backup with iOS first")

enum class BackupAccountStatus { MATCHED, UNBOUND, UNVERIFIED, MISMATCH }
class BackupAccountMismatch : IllegalStateException("Backup belongs to another account")
class BackupAccountUnverified : IllegalStateException("Backup account requires explicit confirmation")
class LocalBackupConflict(val count: Int) : IllegalStateException("Local snapshots conflict")
object BackupAccountRules {
    fun validScope(value: String): Boolean = value.matches(Regex("[a-f0-9]{64}:[A-Za-z0-9._-]{1,128}"))
    fun status(source: BackupPreview, current: String?): BackupAccountStatus = when {
        source.sourceAccountScope != null && current != null && source.sourceAccountScope != current -> BackupAccountStatus.MISMATCH
        current != null && (!source.hasAccountProvenance || source.sourceAccountScope == null) -> BackupAccountStatus.UNVERIFIED
        source.sourceAccountScope == null -> BackupAccountStatus.UNBOUND
        else -> BackupAccountStatus.MATCHED
    }
    fun validate(source: BackupPreview, current: String?, allowUnverified: Boolean) {
        if (source.sourceAccountScope?.let { !validScope(it) } == true) throw InvalidBackupException("Invalid backup account provenance")
        when (status(source, current)) {
            BackupAccountStatus.MISMATCH -> throw BackupAccountMismatch()
            BackupAccountStatus.UNVERIFIED -> if (!allowUnverified) throw BackupAccountUnverified()
            else -> Unit
        }
    }
}
