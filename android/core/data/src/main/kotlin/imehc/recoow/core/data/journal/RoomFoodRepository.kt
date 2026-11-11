package imehc.recoow.core.data.journal

import androidx.room.withTransaction
import imehc.recoow.core.data.media.RoomMediaRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.domain.food.*
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.*
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class RoomFoodRepository(private val host: DatabaseHost, private val media: RoomMediaRepository, deviceId: String,
    now: () -> Long = System::currentTimeMillis) : FoodRepository {
    private val storage = JournalStorage(host, deviceId, now)
    private val tables = listOf("food_entries", "food_day_records", "media_attachments")
    override val snapshot = host.observe { it.records().observeTables(tables) }.map(::decode).flowOn(Dispatchers.Default)
    private fun decode(rows: List<EntitySnapshot>) = FoodSnapshot(
        rows.filter { it.tableName == "food_entries" && it.fields().live }.map { row ->
            val f = row.fields(); val attachments = rows.owned(AttachmentOwnerType.FOOD_ENTRY, row.entityId)
            val ids = (listOfNotNull(f.text("bill_id")) + f.array("bill_ids_json").mapNotNull { it.jsonPrimitive.contentOrNull }).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            FoodEntry(row.entityId, revision(listOf(row) + attachments), f.text("title")!!,
                MealKind.entries.firstOrNull { it.wire == f.text("meal_kind") } ?: MealKind.OTHER, f.long("occurred_at")!!,
                f.text("portion"), f.text("note"), ids, attachments.attachments())
        }.sortedWith(compareByDescending<FoodEntry> { it.occurredAt }.thenByDescending { it.id }),
        rows.filter { it.tableName == "food_day_records" && it.fields().live }.map { FoodDayRecord(it.entityId, revision(listOf(it)), it.fields().long("day_start_at")!!, it.fields().text("title")!!) })
    private suspend fun current(db: RecoowDatabase) = decode(storage.all(db, tables))
    override suspend fun save(draft: FoodDraft): String = host.access { db -> db.withTransaction {
        require(draft.valid)
        storage.check(db.records().snapshot("food_entries", draft.id), draft.revision,
            db.records().snapshots("media_attachments").owned(AttachmentOwnerType.FOOD_ENTRY, draft.id))
        val prior = current(db).entries.firstOrNull { it.id == draft.id }
        val ids = draft.billIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (id in ids) if (id !in prior?.billIds.orEmpty()) require(db.records().snapshot("bills", id)?.fields()?.live == true)
        storage.writer.saveInTransaction(db, listOf(entryChange(draft.copy(billIds = ids))))
        media.saveAttachmentsInTransaction(db, AttachmentOwner(AttachmentOwnerType.FOOD_ENTRY, draft.id), draft.attachments)
        draft.id
    } }
    private fun entryChange(d: FoodDraft) = storage.change("food_entries", d.id, buildJsonObject {
        put("title", d.title.trim()); put("meal_kind", d.meal.wire); put("occurred_at", d.occurredAt)
        put("portion", d.portion.normalized().json()); put("note", d.note.normalized().json())
        put("bill_id", d.billIds.firstOrNull().json()); put("bill_ids_json", JsonArray(d.billIds.map(::JsonPrimitive)).toString())
    })
    override suspend fun delete(id: String, revision: String) = host.access { db -> db.withTransaction {
        storage.deleteOwner(db, AttachmentOwnerType.FOOD_ENTRY, id, revision)
    } }
    override suspend fun duplicate(entries: Map<String, String>, day: LocalDate, zone: ZoneId) = host.access { db -> db.withTransaction {
        require(entries.isNotEmpty())
        val all = current(db).entries.associateBy { it.id }
        val drafts = entries.map { (id, expected) -> val entry = all[id] ?: throw StaleRecordException()
            if (entry.revision != expected) throw StaleRecordException(); FoodDraft.duplicate(entry, day, zone) }
        storage.writer.saveInTransaction(db, drafts.map(::entryChange))
    } }
    override suspend fun saveDayTitle(dayStartAt: Long, title: String, revision: String?) = host.access { db -> db.withTransaction {
        val id = foodDayId(dayStartAt); val row = db.records().snapshot("food_day_records", id)
        // 重建已删除的某一天时，沿用跨端稳定的 ID。
        if (row?.fields()?.live == true || revision != null) storage.check(row, revision)
        if (title.isBlank() && row?.fields()?.live != true) return@withTransaction
        storage.writer.saveInTransaction(db, listOf(storage.change("food_day_records", id, buildJsonObject { put("title", title.trim()); put("day_start_at", dayStartAt) })))
    } }
    override suspend fun deleteDay(day: LocalDate, zone: ZoneId, entries: Map<String, String>, dayRevision: String?) = host.access { db -> db.withTransaction {
        val group = current(db).days(zone).firstOrNull { it.date == day } ?: throw StaleRecordException()
        if (group.entries.associate { it.id to it.revision } != entries || group.record?.revision != dayRevision) throw StaleRecordException()
        for ((id, revision) in entries) storage.deleteOwner(db, AttachmentOwnerType.FOOD_ENTRY, id, revision)
        group.record?.let { storage.writer.saveInTransaction(db, listOf(storage.change("food_day_records", it.id, deleted = true))) }
        Unit
    } }
}
