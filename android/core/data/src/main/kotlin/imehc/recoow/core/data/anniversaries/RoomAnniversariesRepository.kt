package imehc.recoow.core.data.anniversaries

import androidx.room.withTransaction
import imehc.recoow.core.data.journal.*
import imehc.recoow.core.database.*
import imehc.recoow.core.domain.anniversaries.*
import imehc.recoow.core.domain.calendar.DateCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class RoomAnniversariesRepository(private val host: DatabaseHost, deviceId: String, now: () -> Long = System::currentTimeMillis) : AnniversariesRepository {
    private val storage = JournalStorage(host, deviceId, now)
    override val records = host.observe { it.records().observe("anniversaries") }.map { rows -> rows.filter { it.fields().live }.map { row ->
        val f = row.fields()
        Anniversary(row.entityId, revision(listOf(row)), f.text("title")!!, f.text("note"),
            AnniversaryCategory.entries.firstOrNull { it.wire == f.text("category") } ?: AnniversaryCategory.OTHER,
            f.long("occurred_at")!!, if (f.text("date_calendar") == "chinese") DateCalendar.CHINESE else DateCalendar.GREGORIAN,
            f["is_yearly"]!!.jsonPrimitive.boolean, f.long("reminder_time_minutes")!!.toInt(), f.long("lead_time_minutes")!!.toInt(), f["is_enabled"]!!.jsonPrimitive.boolean)
    } }.flowOn(Dispatchers.Default)
    override suspend fun save(draft: AnniversaryDraft): String = host.access { db -> db.withTransaction {
        require(draft.valid); storage.check(db.records().snapshot("anniversaries", draft.id), draft.revision)
        storage.writer.saveInTransaction(db, listOf(storage.change("anniversaries", draft.id, buildJsonObject {
            put("title", draft.title.trim()); put("note", draft.note.normalized().json()); put("category", draft.category.wire)
            put("occurred_at", draft.occurredAt); put("date_calendar", if (draft.calendar == DateCalendar.CHINESE) "chinese" else "gregorian")
            put("is_yearly", draft.yearly); put("reminder_time_minutes", draft.minutes); put("lead_time_minutes", draft.lead); put("is_enabled", draft.enabled)
        }))); draft.id
    } }
    override suspend fun delete(id: String, revision: String) = host.access { db -> db.withTransaction {
        storage.check(db.records().snapshot("anniversaries", id), revision)
        storage.writer.saveInTransaction(db, listOf(storage.change("anniversaries", id, deleted = true)))
    } }
}
