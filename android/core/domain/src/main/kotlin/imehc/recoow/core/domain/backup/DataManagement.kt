package imehc.recoow.core.domain.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Stable tool IDs are shared with iOS, deep links and home visibility preferences. */
enum class ImportScope(val toolId: String, val tables: Set<String>) {
    BILLS("bills", setOf("stored_value_accounts", "stored_value_adjustments", "bills")),
    REMINDERS("reminders", setOf("reminders", "reminder_completions")),
    DIARY("diary", setOf("diary_entries", "diary_tags", "diary_links")),
    FOOD("foodJournal", setOf("food_entries", "food_day_records")),
    DECISION("decisionMaker", setOf("decision_collections", "decision_options", "decision_choice_records")),
    ITEMS("itemLocator", setOf("item_categories", "stored_items")),
    ANNIVERSARIES("anniversaries", setOf("anniversaries")),
    LOCATION("locationTracker", setOf("tracks", "track_points", "track_segments")),
}
enum class ImportMode { MERGE_MISSING, REPLACE_ALL }
data class PreparedBackup(val file: File, val checksum: String, val preview: BackupPreview)
data class StorageUsage(val database: Long, val media: Long, val backups: Long, val cache: Long, val embedded: Long,
    val lastExport: Long?, val lastImport: Long?, val schemaVersion: Int) {
    val total: Long get() = database + media + backups + cache
}
interface DataManagement {
    suspend fun usage(): StorageUsage
    suspend fun export(openDestination: () -> OutputStream)
    suspend fun preview(openSource: () -> InputStream): PreparedBackup
    suspend fun import(backup: PreparedBackup, mode: ImportMode, scopes: Set<ImportScope>): Int
    suspend fun optimize(): Long
    suspend fun reset()
    fun discard(backup: PreparedBackup)
}
