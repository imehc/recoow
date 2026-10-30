package imehc.recoow.core.data.reminders

import android.graphics.BitmapFactory
import androidx.room.withTransaction
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.domain.records.RecordChange
import java.io.File
import java.security.MessageDigest
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class RoomRemindersRepository(private val host: DatabaseHost, private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis, private val zone: () -> ZoneId = ZoneId::systemDefault) : RemindersRepository {
    private val writer = RoomRecordRepository(host, zone)
    override val reminders = host.observe { db -> combine(db.records().observe("reminders"), db.records().observe("reminder_completions"),
        db.records().observeRemindersWithInlineImages()) { rows, details, images ->
        val inline = images.toSet()
        val grouped = details.groupBy { it.fields().text("reminder_id") }
        rows.filter { it.fields().live }.map { it.reminder(grouped[it.entityId].orEmpty(), it.entityId in inline) }
            .sortedWith(compareByDescending<Reminder> { it.scheduledAt }.thenByDescending { it.createdAt }.thenBy { it.id })
    } }.flowOn(Dispatchers.Default)

    override suspend fun save(draft: ReminderDraft): String = host.access { db -> db.withTransaction {
        val row = db.records().snapshot("reminders", draft.id)
        val previous = row?.let { current(db, it.entityId) }
        if ((row == null) != (draft.revision == null) || row != null && (previous == null || previous.revision != draft.revision)) throw StaleReminderException()
        require(draft.valid(previous, now(), zone()))
        val record = requireNotNull(draft.record(previous, zone()))
        val asset = draft.imageAssetId.takeIf { draft.imageEdit in setOf(ReminderImageEdit.KEEP, ReminderImageEdit.ASSET) }
        if (draft.imageEdit == ReminderImageEdit.ASSET) require(asset != null)
        asset?.let { require(db.records().snapshot("media_assets", it)?.fields()?.live == true) }
        val binary = when (draft.imageEdit) {
            ReminderImageEdit.KEEP -> emptyMap()
            ReminderImageEdit.CLEAR, ReminderImageEdit.ASSET -> mapOf("image_data" to null)
            ReminderImageEdit.FILE -> {
                val file = File(requireNotNull(draft.imageFile)); require(file.length() in 1..16L * 1024 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048 && bounds.outMimeType == "image/jpeg")
                mapOf("image_data" to file.readBytes())
            }
        }
        val changes = mutableListOf(authored(record.copy(imageAssetId = asset), binary = binary))
        previous?.let { changes += legacyDetails(db, it) }
        val inherited = if (previous == null && draft.imageEdit == ReminderImageEdit.KEEP && asset == null && draft.hasImage) {
            val source = requireNotNull(db.records().snapshot("reminders", requireNotNull(draft.imageSourceId)))
            require(source.fields().live)
            var ref = source.fields()["_media"]?.jsonObject?.get("image_data")
            if (ref == null) {
                val temporary = File.createTempFile("reminder-copy-", ".tmp", host.directory)
                try {
                    val size = PhysicalRecords.copyBinary(db.openHelper.writableDatabase, "reminders", source.entityId, "image_data", temporary)
                    if (size != null && size > 0) ref = MediaObjects(host.mediaDirectory).capture(temporary, "image/jpeg").json()
                } finally { temporary.delete() }
            }
            ref?.let { mapOf(draft.id to buildJsonObject { put("image_data", it) }) }.orEmpty()
        } else emptyMap()
        writer.saveInTransaction(db, changes, inherited)
        draft.id
    } }

    override suspend fun act(id: String, action: ReminderAction, reason: String) = host.access { db -> db.withTransaction {
        val r = requireNotNull(current(db, id)); val clock = now(); val zone = zone()
        val today = Instant.ofEpochMilli(clock).atZone(zone).toLocalDate()
        val changes = legacyDetails(db, r).toMutableList()
        when (action) {
            ReminderAction.CHECK_IN -> { require(r.canCheckIn(clock, zone)); changes += completion(r.id, CheckIn(today, clock, CheckInKind.CHECK_IN)) }
            ReminderAction.UNDO_TODAY -> { require(r.occurrence(today, zone) != null); changes += completion(r.id, r.completions.single { it.date == today }, deleted = true) }
            ReminderAction.RESTORE -> {
                require(r.canRestore(zone)); changes += r.completions.map { completion(id, it, deleted = true) }
                if (r.completions.isEmpty()) changes += authored(r.copy(completedAt = null))
            }
            ReminderAction.PAUSE -> { require(r.kind == ScheduleKind.GOAL && !r.paused && !r.completed(zone) && reason.isNotBlank()); changes += authored(r.copy(pausedAt = clock, pauseReason = reason.trim())) }
            ReminderAction.RESUME -> { require(r.paused && !r.completed(zone)); changes += authored(r.copy(pausedAt = null, pauseReason = null,
                pausedMillis = Math.addExact(r.pausedMillis, (clock - requireNotNull(r.pausedAt)).coerceAtLeast(0)))) }
            ReminderAction.END_EARLY -> { require(r.kind in setOf(ScheduleKind.GOAL, ScheduleKind.CONTINUOUS) && !r.completed(zone) && reason.isNotBlank())
                changes += authored(r.copy(endedAt = clock, endReason = reason.trim(), pausedAt = null, pauseReason = null, completedAt = null)) }
        }
        writer.saveInTransaction(db, changes)
    } }
    override suspend fun makeUp(id: String, dates: Set<LocalDate>, note: String) = host.access { db -> db.withTransaction {
        val r = requireNotNull(current(db, id)); val clock = now()
        val allowed = dates.filter { r.canMakeUp(it, clock, zone()) }
        require(allowed.isNotEmpty())
        writer.saveInTransaction(db, legacyDetails(db, r) + allowed.sorted().map { completion(id, CheckIn(it, clock, CheckInKind.MAKE_UP, note.trim().ifEmpty { null })) })
    } }
    override suspend fun deleteCompletion(id: String, date: LocalDate) = host.access { db -> db.withTransaction {
        val r = requireNotNull(current(db, id)); val detail = r.completions.single { it.date == date }
        writer.saveInTransaction(db, legacyDetails(db, r) + completion(id, detail, deleted = true))
    } }
    override suspend fun delete(id: String, revision: String) = host.access { db -> db.withTransaction {
        val r = current(db, id) ?: throw StaleReminderException()
        if (r.revision != revision) throw StaleReminderException()
        writer.saveInTransaction(db, listOf(authored(r.copy(enabled = false), deleted = true)))
    } }
    private fun authored(r: Reminder, deleted: Boolean = false, binary: Map<String, ByteArray?> = emptyMap()) = change("reminders", r.id, buildJsonObject {
        put("title", r.title); put("note", r.note.json()); put("memory_icon", r.icon); put("image_asset_id", r.imageAssetId.json())
        put("scheduled_at", r.scheduledAt); put("lead_time_minutes", r.lead); put("is_enabled", r.enabled); put("schedule_kind", r.kind.wire)
        put("end_at", r.endAt.json()); put("reminder_time_minutes", r.timeMinutes); put("weekdays", r.weekdays.sorted().joinToString(",").ifEmpty { null }.json())
        put("continuous_days", r.targetDays); put("imported_completed_days", r.importedDays); put("completed_at", r.completedAt.json())
        put("ended_early_at", r.endedAt.json()); put("early_end_reason", r.endReason.json()); put("paused_at", r.pausedAt.json())
        put("pause_reason", r.pauseReason.json()); put("total_paused_milliseconds", r.pausedMillis)
    }, deleted, binary)
    private fun completion(id: String, c: CheckIn, deleted: Boolean = false) = change("reminder_completions", "$id:${c.date}", buildJsonObject {
        put("reminder_id", id); put("date_key", c.date.toString()); put("completed_at", c.completedAt); put("kind", c.kind.wire); put("note", c.note.json())
    }, deleted)
    // Legacy parent JSON must be materialized before the first detail mutation; existing tombstones always win.
    private suspend fun legacyDetails(db: RecoowDatabase, r: Reminder): List<RecordChange> = r.completions.filter {
        db.records().snapshot("reminder_completions", "${r.id}:${it.date}") == null
    }.map { completion(r.id, it) }
    private fun change(table: String, id: String, fields: JsonObject, deleted: Boolean = false, binary: Map<String, ByteArray?> = emptyMap()) = RecordChange(table, id, fields.toString(), now(), deviceId, deleted, binary)
    private suspend fun current(db: RecoowDatabase, id: String): Reminder? = db.records().snapshot("reminders", id)?.takeIf { it.fields().live }?.reminder(
        db.records().snapshots("reminder_completions").filter { it.fields().text("reminder_id") == id })
    private fun EntitySnapshot.reminder(details: List<EntitySnapshot>, inline: Boolean = false): Reminder {
        val f = fields(); val completions = mutableMapOf<LocalDate, CheckIn>()
        f.text("completed_date_keys")?.split(",")?.filter { it.isNotBlank() }?.forEach { key ->
            val day = LocalDate.parse(key); completions[day] = CheckIn(day, f.long("updated_at")!!, CheckInKind.CHECK_IN)
        }
        f.text("completion_records")?.let { Json.parseToJsonElement(it).jsonArray }?.forEach { value ->
            val c = value.jsonObject; val day = LocalDate.parse(c.text("dateKey")); completions[day] = CheckIn(day, c.long("completedAt")!!,
                if (c.text("kindRawValue") == "makeUp") CheckInKind.MAKE_UP else CheckInKind.CHECK_IN, c.text("note"))
        }
        for (detail in details) { val c = detail.fields(); val day = LocalDate.parse(c.text("date_key"))
            if (!c.live) completions.remove(day) else completions[day] = CheckIn(day, c.long("completed_at")!!,
                if (c.text("kind") == "makeUp") CheckInKind.MAKE_UP else CheckInKind.CHECK_IN, c.text("note"))
        }
        val revision = MessageDigest.getInstance("SHA-256").digest((json + details.sortedBy { it.entityId }.joinToString { it.json }).toByteArray()).joinToString("") { "%02x".format(it) }
        return Reminder(entityId, revision, f.text("title")!!, f.long("scheduled_at")!!, f.long("created_at")!!, f.long("updated_at")!!,
            f.text("note"), f.text("memory_icon") ?: "🔔", f.text("image_asset_id"), inline || f.text("image_asset_id") != null || f["_media"]?.jsonObject?.get("image_data")?.let { it != JsonNull } == true,
            ScheduleKind.entries.firstOrNull { it.wire == f.text("schedule_kind") } ?: ScheduleKind.SINGLE, f.long("end_at"), f.long("reminder_time_minutes")!!.toInt(),
            f.text("weekdays")?.split(",")?.mapNotNull { it.toIntOrNull()?.takeIf { it in 1..7 } }?.toSet().orEmpty(), f.long("continuous_days")!!,
            f.long("imported_completed_days") ?: 0, f["is_enabled"]!!.jsonPrimitive.boolean, (f.long("lead_time_minutes") ?: 0).toInt(),
            f.long("completed_at"), f.long("ended_early_at"), f.text("early_end_reason"), f.long("paused_at"), f.text("pause_reason"),
            f.long("total_paused_milliseconds") ?: 0, completions.values.sortedBy { it.date })
    }
    private fun EntitySnapshot.fields() = Json.parseToJsonElement(json).jsonObject
    private val JsonObject.live get() = this["deleted_at"] == JsonNull
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
    private fun String?.json() = this?.let(::JsonPrimitive) ?: JsonNull
    private fun Long?.json() = this?.let(::JsonPrimitive) ?: JsonNull
}
