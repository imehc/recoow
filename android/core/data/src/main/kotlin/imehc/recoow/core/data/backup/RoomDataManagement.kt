package imehc.recoow.core.data.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import imehc.recoow.core.backup.IosBackupInspector
import imehc.recoow.core.data.records.AggregateProjection
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.domain.backup.*
import imehc.recoow.core.domain.sync.DeviceIdentity
import java.io.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.json.JSONObject
import kotlin.coroutines.CoroutineContext

/** Converts a portable SQLite snapshot; the live Room schema is never replaced by an iOS file. */
@Singleton
class RoomDataManagement @Inject constructor(@param:ApplicationContext private val context: Context,
    private val host: DatabaseHost, private val preferences: DataStore<Preferences>, private val identity: DeviceIdentity) : DataManagement {
    private val lock = Mutex()
    private val staging get() = File(context.cacheDir, "backup-transfer").apply { mkdirs() }
    private val rollback get() = File(context.noBackupFilesDir, "rollback")
    private val portable = PortablePreferences(context, preferences)
    private val contracts get() = EntityContracts.registry.entities
    private val tables get() = contracts.keys

    private suspend fun accountScope(db: RecoowDatabase): String? = db.records().activeScope()?.let { db.records().target(it) }?.let {
        it.connectionId.substringBefore(':') + ":" + it.accountId
    }

    override suspend fun usage(): StorageUsage = host.access { db -> usage(db) }
    private suspend fun usage(db: RecoowDatabase): StorageUsage {
        val values = preferences.data.first()
        val sql = db.openHelper.writableDatabase
        val embedded = contracts.values.sumOf { contract -> contract.fields.filter { it.binary }.sumOf { field ->
            sql.query("SELECT coalesce(sum(length(\"${field.name}\")),0) FROM \"${contract.table}\"").use { it.moveToFirst(); it.getLong(0) }
        } }
        return StorageUsage(listOf("", "-wal", "-shm", "-journal").sumOf { File(host.databaseFile.path + it).length() },
            bytes(host.mediaDirectory), bytes(rollback), bytes(context.cacheDir), embedded,
            values[LAST_EXPORT], values[LAST_IMPORT], DatabaseHost.VERSION)
    }

    override suspend fun export(openDestination: () -> OutputStream): Unit = withContext(Dispatchers.IO) { lock.withLock {
        val file = File.createTempFile("export-", ".recoowbackup", staging)
        try {
            host.access {
                // Read DataStore before opening the thread-bound SQLite transaction.
                val preferenceSnapshot = portable.capture()
                val cancellation = currentCoroutineContext()
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { output ->
                    context.assets.open("ios-backup-schema-v37.sql").bufferedReader().use { reader ->
                        reader.readText().lineSequence().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
                            .split(';').filter { it.isNotBlank() }.forEach(output::execSQL)
                    }
                    output.execSQL("ATTACH DATABASE ? AS live", arrayOf(host.databaseFile.path))
                    writeExport(output, preferenceSnapshot, cancellation)
                    output.execSQL("DETACH DATABASE live")
                    output.rawQuery("PRAGMA foreign_key_check", null).use { check(!it.moveToFirst()) { "Invalid backup references" } }
                }
            }
            validate(file)
            openDestination().use { output -> file.inputStream().use { it.copyTo(output) }; output.flush() }
            host.access { preferences.edit { it[LAST_EXPORT] = System.currentTimeMillis() }; Unit }
        } finally { removeDatabase(file) }
    } }

    // Deliberately non-suspending: SQLiteDatabase transactions belong to their starting thread.
    private fun writeExport(output: SQLiteDatabase, preferenceSnapshot: JSONObject, cancellation: CoroutineContext) {
        output.beginTransaction()
        try {
            for (table in tables) {
                cancellation.ensureActive()
                val columns = columns(output, table).intersect(columns(output, table, "live")).joinToString { "\"$it\"" }
                output.execSQL("INSERT INTO \"$table\" ($columns) SELECT $columns FROM live.\"$table\"")
            }
            output.execSQL("INSERT INTO record_origins SELECT * FROM live.record_origins")
            // Read immutable objects only. Export must never fill inline_data in the online database.
            for (contract in contracts.values) output.rawQuery(
                "SELECT entityId, json FROM live.entity_snapshots WHERE tableName=? ORDER BY entityId", arrayOf(contract.table)
            ).use { rows -> while (rows.moveToNext()) {
                cancellation.ensureActive()
                val id = rows.getString(0)
                val snapshot = Json.parseToJsonElement(rows.getString(1)).jsonObject
                val media = snapshot["_media"]?.jsonObject.orEmpty()
                for ((key, value) in media) {
                    if (value == JsonNull) continue
                    val column = if (contract.table == "media_assets" && key == "object") "inline_data" else key
                    require(contract.fields.any { it.name == column && it.binary })
                    val reference = MediaReference.parse(value)
                    val source = MediaObjects(host.mediaDirectory).verified(reference)
                    embed(output, contract.table, id, column, source, cancellation)
                }
                if (contract.table == "media_assets" && media["object"] == null) {
                    val key = snapshot["storage_key"]?.jsonPrimitive?.contentOrNull
                    val source = key?.let { safeMedia(host.mediaDirectory, it) }
                    if (source?.isFile == true) embed(output, contract.table, id, "inline_data", source, cancellation)
                }
            } }
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION") val build = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            val metadata = JSONObject().put("formatVersion", 1).put("databaseSchemaVersion", 37)
                .put("sourcePlatform", "android").put("sourceSchemaVersion", DatabaseHost.VERSION)
                .put("appVersion", info.versionName ?: "unknown").put("appBuild", build.toString())
                .put("exportedAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()).put("sourceDeviceID", identity.id).put("preferences", preferenceSnapshot)
            val owner = output.rawQuery("SELECT t.connectionId,t.accountId FROM live.sync_targets t JOIN live.dataset_state d ON t.scope=d.activeScope LIMIT 1", null).use {
                if (it.moveToFirst()) it.getString(0).substringBefore(':') + ":" + it.getString(1) else null
            }
            metadata.put("sourceAccountScope", owner ?: JSONObject.NULL)
            output.execSQL("INSERT OR REPLACE INTO _recoow_backup_metadata(key,value) VALUES ('metadata',?)", arrayOf(metadata.toString()))
            output.setTransactionSuccessful()
        } finally { output.endTransaction() }
    }

    override suspend fun preview(openSource: () -> InputStream): PreparedBackup = withContext(Dispatchers.IO) { lock.withLock {
        val file = File.createTempFile("incoming-", ".recoowbackup", staging)
        try {
            openSource().use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    total += n; require(total <= MAX_BACKUP) { "Backup exceeds 2 GiB" }; output.write(buffer, 0, n)
                }
                output.fd.sync()
            } }
            val preview = validate(file)
            val current = host.access { db -> accountScope(db) }
            PreparedBackup(file, MediaObjects.checksum(file), preview, BackupAccountRules.status(preview, current))
        } catch (error: Throwable) { removeDatabase(file); throw error }
    } }

    private suspend fun validate(file: File): BackupPreview {
        val preview = IosBackupInspector(staging).inspect({ file.inputStream() })
        // Pre-sync development backups need the iOS migrator's historical business backfills first.
        if (preview.databaseSchemaVersion < 34) throw LegacyBackupException()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { source ->
            require(tables.all { it in preview.tableCounts }) { "Incomplete backup" }
            source.rawQuery("PRAGMA foreign_key_check", null).use { require(!it.moveToFirst()) { "Invalid backup references" } }
            source.rawQuery("SELECT id, checksum, byte_count, length(inline_data), deleted_at FROM media_assets", null).use { rows ->
                while (rows.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (rows.isNull(3)) { require(!rows.isNull(4)) { "Missing media in backup" }; continue }
                    require(rows.getLong(2) == rows.getLong(3) && rows.getLong(3) in 0..MAX_MEDIA) { "Invalid media length" }
                    require(hashBlob(source, "media_assets", rows.getString(0), "inline_data", rows.getLong(3)) == rows.getString(1)) { "Damaged backup media" }
                }
            }
            for (contract in contracts.values) {
                val available = columns(source, contract.table)
                require(contract.fields.filter { it.required && !it.binary && !it.derived }.all { it.name in available }) { "Missing ${contract.table} columns" }
                for (field in contract.fields.filter { it.binary && it.name in available }) source.rawQuery(
                    "SELECT 1 FROM \"${contract.table}\" WHERE length(\"${field.name}\") > $MAX_MEDIA LIMIT 1", null).use { require(!it.moveToFirst()) { "Oversized media" } }
            }
        }
        return preview
    }

    override suspend fun import(backup: PreparedBackup, mode: ImportMode, scopes: Set<ImportScope>, allowUnverifiedAccount: Boolean): Int = withContext(Dispatchers.IO) { lock.withLock {
        require(mode == ImportMode.REPLACE_ALL || scopes.isNotEmpty())
        require(backup.file.parentFile?.canonicalFile == staging.canonicalFile && MediaObjects.checksum(backup.file) == backup.checksum) { "Backup changed; preview it again" }
        validate(backup.file)
        host.guarded("import-${mode.name.lowercase()}") { db, root ->
            BackupAccountRules.validate(backup.preview, accountScope(db), allowUnverifiedAccount)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("ATTACH DATABASE ? AS incoming", arrayOf(backup.file.path))
            try {
                val incomingHasOrigins = sql.query("SELECT 1 FROM incoming.sqlite_master WHERE type = 'table' AND name = 'record_origins'").use { it.moveToFirst() }
                var imported = 0
                db.withTransaction {
                    if (mode == ImportMode.REPLACE_ALL) clear(db, keepBinding = true)
                    val selected = if (mode == ImportMode.REPLACE_ALL) ImportScope.entries.toSet() else scopes
                    val selectedTables = selected.flatMap { it.tables }.toSet()
                    if (mode == ImportMode.MERGE_MISSING) {
                        for (table in selectedTables) {
                            val available = sql.query("PRAGMA incoming.table_info(\"$table\")").use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(1)) } }
                            val fields = contracts.getValue(table).fields.filter { it.name in available && !it.derived && it.name !in setOf("id","created_at","updated_at","device_id","server_version","storage_key") && !(table == "food_entries" && it.name in setOf("bill_id", "bill_ids_json")) }
                            val different = fields.joinToString(" OR ") {
                                val pair = "dst.\"${it.name}\" IS NOT src.\"${it.name}\""
                                if (table == "media_assets" && it.name == "inline_data") "(dst.\"${it.name}\" IS NOT NULL AND src.\"${it.name}\" IS NOT NULL AND $pair)" else "($pair)"
                            }
                            if (different.isNotEmpty()) {
                                val count = sql.query("SELECT COUNT(*) FROM incoming.\"$table\" src JOIN \"$table\" dst ON dst.id=src.id WHERE $different").use { it.moveToFirst(); it.getInt(0) }
                                if (count > 0) throw LocalBackupConflict(count)
                            }
                            if (table == "food_entries") {
                                // Compare the references that this scoped import would actually retain.
                                // API26 SQLite has no JSON1, so filter the ordered bill list in Kotlin.
                                val incomingBills = if ("bills" in selectedTables) " OR EXISTS(SELECT 1 FROM incoming.bills WHERE id=?)" else ""
                                sql.compileStatement("SELECT EXISTS(SELECT 1 FROM bills WHERE id=?)$incomingBills").use { exists ->
                                    fun retained(id: String): Boolean {
                                        exists.bindString(1, id)
                                        if (incomingBills.isNotEmpty()) exists.bindString(2, id)
                                        return exists.simpleQueryForLong() != 0L
                                    }
                                    sql.query("SELECT src.bill_id, src.bill_ids_json, dst.bill_id, dst.bill_ids_json FROM incoming.food_entries src JOIN food_entries dst ON dst.id=src.id").use { rows ->
                                        while (rows.moveToNext()) {
                                            val expected = rows.getString(0)?.takeIf(::retained)
                                            val links = Json.parseToJsonElement(rows.getString(1)).jsonArray.filter { link ->
                                                require(link is JsonPrimitive && link.isString) { "Invalid food bill reference" }
                                                retained(link.content)
                                            }
                                            if (expected != rows.getString(2) || JsonArray(links) != Json.parseToJsonElement(rows.getString(3))) throw LocalBackupConflict(1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Temporary ID sets make scope selection independent of insertion order and existing IDs.
                    for (table in tables) {
                        sql.execSQL("CREATE TEMP TABLE \"import_$table\" (id TEXT PRIMARY KEY)")
                        val predicate = if (mode == ImportMode.REPLACE_ALL) "1" else selection(table, selected, selectedTables)
                        if (predicate != null) sql.execSQL("INSERT INTO \"import_$table\" SELECT src.id FROM incoming.\"$table\" src WHERE ($predicate) AND NOT EXISTS(SELECT 1 FROM \"$table\" dst WHERE dst.id=src.id)")
                    }
                    for (table in tables) {
                        val available = sql.query("PRAGMA incoming.table_info(\"$table\")").use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(1)) } }
                        val common = contracts.getValue(table).fields.map { it.name }.filter { it in available } + "sync_status"
                        val fields = common.distinct()
                        val expressions = fields.map { column -> when {
                            column == "sync_status" -> "0"
                            column == "server_version" -> "NULL"
                            table == "food_entries" && column == "bill_id" -> "CASE WHEN EXISTS(SELECT 1 FROM bills WHERE id=src.bill_id) OR EXISTS(SELECT 1 FROM import_bills WHERE id=src.bill_id) THEN src.bill_id ELSE NULL END"
                            else -> "src.\"$column\""
                        } }
                        sql.execSQL("INSERT INTO \"$table\" (${fields.joinToString { "\"$it\"" }}) SELECT ${expressions.joinToString()} FROM incoming.\"$table\" src JOIN \"import_$table\" chosen ON src.id=chosen.id")
                    }
                    // API26's system SQLite has no JSON1 extension. Filter after every selected
                    // table is inserted, so both existing and newly imported bills are available.
                    sql.compileStatement("SELECT EXISTS(SELECT 1 FROM bills WHERE id=?)").use { billExists ->
                        sql.query("SELECT r.id, r.bill_ids_json FROM food_entries r JOIN import_food_entries chosen ON r.id=chosen.id").use { rows ->
                            while (rows.moveToNext()) {
                                currentCoroutineContext().ensureActive()
                                val links = Json.parseToJsonElement(rows.getString(1)).jsonArray.filter { link ->
                                    require(link is JsonPrimitive && link.isString) { "Invalid food bill reference" }
                                    billExists.bindString(1, link.content)
                                    billExists.simpleQueryForLong() != 0L
                                }
                                sql.execSQL("UPDATE food_entries SET bill_ids_json=? WHERE id=?",
                                    arrayOf(JsonArray(links).toString(), rows.getString(0)))
                            }
                        }
                    }
                    for (table in tables) {
                        val contract = contracts.getValue(table)
                        val fields = contract.fields.filterNot { it.binary }
                        sql.query("SELECT ${fields.joinToString { "r.\"${it.name}\"" }} FROM \"$table\" r JOIN \"import_$table\" chosen ON r.id=chosen.id").use { rows ->
                            while (rows.moveToNext()) {
                                currentCoroutineContext().ensureActive()
                                val raw = buildJsonObject { fields.forEachIndexed { index, field ->
                                    if (!rows.isNull(index)) require(when (field.type) {
                                        "int", "bool" -> rows.getType(index) == Cursor.FIELD_TYPE_INTEGER
                                        "real" -> rows.getType(index) in setOf(Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT)
                                        else -> rows.getType(index) == Cursor.FIELD_TYPE_STRING
                                    }) { "Invalid stored type: $table.${field.name}" }
                                    put(field.name, when {
                                    rows.isNull(index) -> JsonNull
                                    field.type == "int" -> JsonPrimitive(rows.getLong(index))
                                    field.type == "real" -> JsonPrimitive(rows.getDouble(index))
                                    field.type == "bool" -> { require(rows.getLong(index) in 0L..1L); JsonPrimitive(rows.getLong(index) == 1L) }
                                    else -> JsonPrimitive(rows.getString(index))
                                }) } }
                                val id = raw.getValue("id").jsonPrimitive.content
                                val snapshot = contract.normalize(raw, contract.version).toMutableMap()
                                val media = mutableMapOf<String, JsonElement>()
                                for (field in contract.fields.filter { it.binary }) {
                                    val scratch = File.createTempFile("media-", ".tmp", staging)
                                    try {
                                        if (PhysicalRecords.copyBinary(sql, table, id, field.name, scratch) != null) {
                                            val ref = MediaObjects(root).capture(scratch, raw["mime_type"]?.jsonPrimitive?.contentOrNull ?: "image/jpeg")
                                            media[if (table == "media_assets") "object" else field.name] = ref.json()
                                            if (table == "media_assets") {
                                                require(ref.checksum == raw.getValue("checksum").jsonPrimitive.content && ref.size == raw.getValue("byte_count").jsonPrimitive.long)
                                                snapshot["storage_key"] = JsonPrimitive(ref.key)
                                                sql.execSQL("UPDATE media_assets SET storage_key=?, inline_data=NULL WHERE id=?", arrayOf(ref.key, id))
                                            }
                                        }
                                    } finally { scratch.delete() }
                                }
                                if (media.isNotEmpty()) snapshot["_media"] = JsonObject(media)
                                val payload = JsonObject(snapshot).toString()
                                db.records().put(EntitySnapshot(table, id, contract.version, payload))
                                val mutation = UUID.randomUUID().toString()
                                val operation = if (raw["deleted_at"] == JsonNull) "update" else "delete"
                                val origin = if (incomingHasOrigins) {
                                    sql.query("SELECT origin_json FROM incoming.record_origins WHERE table_name = ? AND entity_id = ? AND device_id = ? AND updated_at = ?", arrayOf<Any>(table, id, snapshot.getValue("device_id").jsonPrimitive.content, snapshot.getValue("updated_at").jsonPrimitive.long)).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
                                } else null
                                db.records().put(RecordOrigin(table, id, snapshot.getValue("device_id").jsonPrimitive.content, snapshot.getValue("updated_at").jsonPrimitive.long, origin))
                                val local = db.records().log(LocalChange(mutationId = mutation, tableName = table, entityId = id,
                                    operation = operation, dataVersion = contract.version, payloadJson = payload, changedAt = raw.getValue("updated_at").jsonPrimitive.long))
                                db.records().activeScope()?.let { scope ->
                                    db.records().enqueue(SyncOutbox(scope, mutation, table, id, operation, contract.version, payload, 0, local, originJson = origin))
                                }
                                RoomRecordRepository.queueNotification(db, table, id, raw.getValue("updated_at").jsonPrimitive.long)
                                imported++
                            }
                        }
                    }
                    AggregateProjection.refresh(db, selectedTables, ZoneId.systemDefault())
                    if (db.records().activeScope() == null) backup.preview.sourceAccountScope?.let { source ->
                        val parts = source.split(':', limit = 2)
                        require(parts.size == 2 && parts[0].matches(Regex("[a-f0-9]{64}")) && parts[1].isNotBlank()) { "Invalid backup account provenance" }
                        val scope = source + ":backup"
                        db.records().put(SyncTarget(scope, parts[0] + ":backup", parts[1], "backup"))
                        db.records().put(DatasetState(activeScope = scope))
                    }
                    if (mode == ImportMode.REPLACE_ALL) portable.restore(JSONObject(backup.preview.metadataJson).getJSONObject("preferences"))
                    preferences.edit { it[LAST_IMPORT] = System.currentTimeMillis() }
                }
                imported
            } finally {
                for (table in tables) sql.execSQL("DROP TABLE IF EXISTS temp.\"import_$table\"")
                sql.execSQL("DETACH DATABASE incoming")
            }
        }
    } }

    private fun selection(table: String, scopes: Set<ImportScope>, selectedTables: Set<String>): String? {
        if (table in selectedTables) return "1"
        val owners = buildList {
            if (ImportScope.FOOD in scopes) add("(src.owner_type='foodEntry' AND EXISTS(SELECT 1 FROM incoming.food_entries WHERE id=src.owner_id AND deleted_at IS NULL))")
            if (ImportScope.DIARY in scopes) add("(src.owner_type='diary' AND EXISTS(SELECT 1 FROM incoming.diary_entries WHERE id=src.owner_id AND deleted_at IS NULL))")
        }
        if (table == "media_attachments") return owners.joinToString(" OR ").ifBlank { null }
        if (table != "media_assets") return null
        if (scopes.size == ImportScope.entries.size) return "1"
        val references = mapOf("bills" to "image_asset_id", "reminders" to "image_asset_id", "stored_items" to "image_asset_id", "decision_options" to "image_asset_id", "decision_choice_records" to "option_image_asset_id")
        val queries = references.filterKeys { it in selectedTables }.map { (owner, field) -> "SELECT $field FROM incoming.$owner" }.toMutableList()
        if (owners.isNotEmpty()) queries += "SELECT asset_id FROM incoming.media_attachments src WHERE ${owners.joinToString(" OR ")}"
        return queries.takeIf { it.isNotEmpty() }?.joinToString(" UNION ")?.let { "src.id IN ($it)" }
    }

    private suspend fun clear(db: RecoowDatabase, keepBinding: Boolean) {
        val sql = db.openHelper.writableDatabase
        for (table in tables) sql.execSQL("DELETE FROM \"$table\"")
        for (table in listOf("record_origins", "entity_snapshots", "local_changes", "notification_jobs", "sync_outbox", "sync_rows", "sync_inbox", "sync_received_pages", "sync_media_jobs", "sync_conflicts")) sql.execSQL("DELETE FROM $table")
        if (keepBinding) sql.execSQL("UPDATE sync_targets SET receivedCursor=0, appliedCursor=0, bootstrapWatermark=0, bootstrapAfter=0, bootstrapComplete=0, seeded=0, failureCount=0, nextAttemptAt=0")
        else { sql.execSQL("DELETE FROM dataset_state"); sql.execSQL("DELETE FROM sync_targets") }
    }

    override suspend fun optimize(): Long = withContext(Dispatchers.IO) { lock.withLock {
        val before = usage().total
        host.guarded("optimize-storage") { db, media ->
            // Retain every historical/sync reference, including tombstones, pending uploads and conflicts.
            val keep = mutableSetOf<String>()
            fun collect(value: JsonElement) { when (value) {
                is JsonObject -> { value["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("sync/") }?.let(keep::add); value.values.forEach { collect(it) } }
                is JsonArray -> value.forEach { collect(it) }
                else -> Unit
            } }
            for ((table, column) in listOf("entity_snapshots" to "json", "local_changes" to "payloadJson", "sync_outbox" to "payloadJson", "sync_outbox" to "preparedJson", "sync_rows" to "envelopeJson", "sync_inbox" to "envelopeJson", "sync_conflicts" to "localJson", "sync_conflicts" to "remoteJson")) {
                db.openHelper.writableDatabase.query("SELECT \"$column\" FROM $table WHERE \"$column\" IS NOT NULL").use { rows -> while (rows.moveToNext()) collect(Json.parseToJsonElement(rows.getString(0))) }
            }
            db.openHelper.writableDatabase.query("SELECT storage_key FROM media_assets UNION SELECT objectKey FROM sync_media_jobs").use { rows -> while (rows.moveToNext()) if (!rows.isNull(0)) keep += rows.getString(0) }
            // An unparsed received page can refer to an object not applied yet; conservatively keep sync objects.
            val pendingPages = db.openHelper.writableDatabase.query("SELECT 1 FROM sync_received_pages LIMIT 1").use { it.moveToFirst() }
            media.walkTopDown().filter { it.isFile }.forEach { file ->
                val key = file.relativeTo(media).invariantSeparatorsPath
                if (key !in keep && !(pendingPages && key.startsWith("sync/"))) check(file.delete())
            }
            db.openHelper.writableDatabase.execSQL("VACUUM")
        }
        // Cache entries can still be held by an editor; only prune old resolved copies and completed transfers.
        for (name in listOf("resolved-media", "sync-transfer", "backup-transfer")) File(context.cacheDir, name).walkTopDown().filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 86_400_000 }.forEach { it.delete() }
        pruneRollbacks()
        (before - usage().total).coerceAtLeast(0)
    } }

    override suspend fun reset(): Unit = withContext(Dispatchers.IO) { lock.withLock {
        host.guarded("reset-local-data") { db, media ->
            db.withTransaction { clear(db, keepBinding = false) }
            if (media.exists()) check(media.deleteRecursively())
            portable.restore(JSONObject().put("hiddenToolRouteIDs", org.json.JSONArray()))
            preferences.edit { it.clear(); it[RESET_PENDING] = true }; Unit
        }
    } }
    suspend fun resetPending(): Boolean = host.access { preferences.data.first()[RESET_PENDING] == true }
    suspend fun finishReset() = host.access {
        if (rollback.exists()) check(rollback.deleteRecursively())
        for (name in listOf("resolved-media", "sync-transfer", "media-drafts", "backup-transfer")) File(context.cacheDir, name).deleteRecursively()
        preferences.edit { it.remove(RESET_PENDING) }; Unit
    }
    override fun discard(backup: PreparedBackup) { if (backup.file.parentFile?.canonicalFile == staging.canonicalFile) removeDatabase(backup.file) }

    private fun pruneRollbacks() {
        if (File(rollback, "pending-restore").exists()) return
        rollback.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("rollback-") }
            .sortedByDescending { it.lastModified() }.drop(1).forEach { it.deleteRecursively() }
    }
    private fun bytes(root: File) = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else root.length()
    private fun columns(db: SQLiteDatabase, table: String, schema: String = "main") = db.rawQuery("PRAGMA $schema.table_info(\"$table\")", null).use { rows -> buildSet { while (rows.moveToNext()) add(rows.getString(1)) } }
    private fun safeMedia(root: File, key: String): File = File(root, key).canonicalFile.also { require(it.path.startsWith(root.canonicalPath + File.separator)) }
    private fun removeDatabase(file: File) { listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() } }
    private fun embed(db: SQLiteDatabase, table: String, id: String, column: String, source: File, cancellation: CoroutineContext) {
        require(source.length() <= MAX_MEDIA)
        db.execSQL("UPDATE \"$table\" SET \"$column\"=X'' WHERE id=?", arrayOf(id))
        source.inputStream().use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                cancellation.ensureActive()
                val n = input.read(buffer); if (n < 0) break
                db.execSQL("UPDATE \"$table\" SET \"$column\"=CAST(\"$column\" || ? AS BLOB) WHERE id=?", arrayOf(buffer.copyOf(n), id))
            }
        }
    }
    private suspend fun hashBlob(db: SQLiteDatabase, table: String, id: String, column: String, length: Long): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256"); var offset = 0L
        while (offset < length) {
            currentCoroutineContext().ensureActive()
            db.rawQuery("SELECT substr(\"$column\",?,?) FROM \"$table\" WHERE id=?", arrayOf((offset+1).toString(), minOf(512*1024L,length-offset).toString(),id)).use {
                check(it.moveToFirst()); val part=it.getBlob(0); check(part.isNotEmpty()); hash.update(part); offset+=part.size
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private companion object {
        const val MAX_BACKUP = 2L * 1024 * 1024 * 1024
        const val MAX_MEDIA = 200L * 1024 * 1024
        val LAST_EXPORT = longPreferencesKey("lastBackupExport")
        val LAST_IMPORT = longPreferencesKey("lastBackupImport")
        val RESET_PENDING = booleanPreferencesKey("localResetPending")
    }
}
