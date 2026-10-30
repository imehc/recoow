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
)

class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

class LegacyBackupException : IllegalArgumentException("Upgrade this backup with iOS first")
