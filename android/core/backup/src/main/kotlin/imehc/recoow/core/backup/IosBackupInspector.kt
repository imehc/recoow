package imehc.recoow.core.backup

import android.database.sqlite.SQLiteDatabase
import imehc.recoow.core.domain.backup.BackupPreview
import imehc.recoow.core.domain.backup.InvalidBackupException
import java.io.File
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Reads a private copy. Inspection never opens or replaces the application's live database. */
class IosBackupInspector(private val temporaryDirectory: File) {
    suspend fun inspect(openSource: () -> InputStream, maximumBytes: Long = 2L * 1024 * 1024 * 1024): BackupPreview =
        withContext(Dispatchers.IO) {
            require(maximumBytes > 0)
            check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs())
            val file = File.createTempFile("recoow-inspect-", ".sqlite", temporaryDirectory)
            try {
                openSource().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > maximumBytes) throw InvalidBackupException("Backup exceeds the configured size limit")
                        output.write(buffer, 0, read)
                    }
                } }
                val header = file.inputStream().use { input -> ByteArray(16).also { input.read(it) } }
                if (!header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) {
                    throw InvalidBackupException("Not a SQLite backup")
                }
                SQLiteDatabase.openDatabase(file.path, null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    db.rawQuery("PRAGMA integrity_check(1)", null).use {
                        if (!it.moveToFirst() || it.getString(0) != "ok") throw InvalidBackupException("Invalid SQLite integrity")
                    }
                    val tables = mutableSetOf<String>()
                    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND sql NOT LIKE 'CREATE VIRTUAL TABLE%'", null).use {
                        while (it.moveToNext()) tables.add(it.getString(0))
                    }
                    if (METADATA_TABLE !in tables) throw InvalidBackupException("Missing backup metadata")
                    val json = db.rawQuery("SELECT value FROM $METADATA_TABLE WHERE key = ? AND length(value) <= 1048576", arrayOf("metadata")).use {
                        if (!it.moveToFirst()) throw InvalidBackupException("Missing or oversized backup metadata")
                        it.getString(0)
                    }
                    val metadata = JSONObject(json)
                    if (metadata.has("sourceAccountScope") && !metadata.isNull("sourceAccountScope")) {
                        val value = metadata.get("sourceAccountScope")
                        if (value !is String || !imehc.recoow.core.domain.backup.BackupAccountRules.validScope(value)) {
                            throw InvalidBackupException("Invalid backup account provenance")
                        }
                    }
                    val format = metadata.getInt("formatVersion")
                    val schema = metadata.getInt("databaseSchemaVersion")
                    if (format != 1) throw InvalidBackupException("Unsupported backup format: $format")
                    if (schema !in 1..37) throw InvalidBackupException("Unsupported iOS database schema: $schema")
                    metadata.getJSONObject("preferences").getJSONArray("hiddenToolRouteIDs")
                    val counts = TABLES.filter { it in tables }.associateWith { table ->
                        currentCoroutineContext().ensureActive()
                        db.rawQuery("SELECT count(*) FROM \"$table\"", null).use { it.moveToFirst(); it.getLong(0) }
                    }
                    val mediaBytes = if ("media_assets" in tables && hasColumn(db, "media_assets", "inline_data")) {
                        // Do not load BLOBs through CursorWindow: a photo can exceed its row limit.
                        db.rawQuery("SELECT coalesce(sum(length(inline_data)), 0) FROM media_assets", null).use { it.moveToFirst(); it.getLong(0) }
                    } else 0L
                    BackupPreview(format, schema, metadata.getString("appVersion"), metadata.getString("appBuild"),
                        Instant.parse(metadata.getString("exportedAt")), metadata.getString("sourceDeviceID"), json, counts, mediaBytes, metadata.optString("sourcePlatform", "iOS"),
                        if (metadata.has("sourceSchemaVersion")) metadata.getInt("sourceSchemaVersion") else schema,
                        if (metadata.has("sourceAccountScope") && !metadata.isNull("sourceAccountScope")) metadata.getString("sourceAccountScope") else null,
                        metadata.has("sourceAccountScope"))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: InvalidBackupException) {
                throw error
            } catch (error: Exception) {
                throw InvalidBackupException("Unable to inspect backup", error)
            } finally {
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                File(file.path + "-journal").delete()
            }
        }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(1) == column) found = true
            found
        }

    private companion object {
        const val METADATA_TABLE = "_recoow_backup_metadata"
        val TABLES = listOf("bills", "stored_value_accounts", "stored_value_adjustments", "reminders",
            "reminder_completions", "diary_entries", "diary_tags", "diary_links", "food_entries", "food_day_records",
            "decision_collections", "decision_options", "decision_choice_records", "item_categories", "stored_items",
            "anniversaries", "tracks", "track_points", "track_segments", "media_assets", "media_attachments")
    }
}
