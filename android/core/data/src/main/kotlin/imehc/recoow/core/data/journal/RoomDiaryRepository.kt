package imehc.recoow.core.data.journal

import android.content.Context
import androidx.room.withTransaction
import imehc.recoow.core.data.media.RoomMediaRepository
import imehc.recoow.core.data.reminders.RoomRemindersRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.domain.diary.*
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.*
import java.util.UUID
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class RoomDiaryRepository(private val host: DatabaseHost, private val media: RoomMediaRepository, deviceId: String,
    private val context: Context, private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault) : DiaryRepository {
    private val storage = JournalStorage(host, deviceId, now)
    private val reminders = RoomRemindersRepository(host, deviceId, now, zone)
    private val tables = listOf("diary_entries", "diary_tags", "diary_links", "media_attachments")
    override val snapshot = host.observe { it.records().observeTables(tables) }.map { rows ->
        DiarySnapshot(rows.filter { it.tableName == "diary_entries" && it.fields().live }.map { row ->
            val f = row.fields(); val links = rows.links(row.entityId); val attachments = rows.owned(AttachmentOwnerType.DIARY, row.entityId)
            DiaryEntry(row.entityId, revision(listOf(row) + links + attachments), f.text("title")!!, f.text("content")!!,
                DiaryMood.entries.firstOrNull { it.wire == f.text("mood") } ?: DiaryMood.CALM, f.long("occurred_at")!!,
                f.array("tags_json").mapNotNull { value -> (value as? JsonObject)?.let { tag ->
                    val key = tag.text("key"); val name = tag.text("value"); if (key != null && name != null) DiaryTagReference(key, name) else null } },
                links.filter { it.fields().live }.sortedBy { it.fields().long("created_at") }.map { it.link() }, attachments.attachments(),
                f.double("latitude"), f.double("longitude"), f.double("horizontal_accuracy"))
        }.sortedWith(compareByDescending<DiaryEntry> { it.occurredAt }.thenByDescending { it.id }),
            rows.filter { it.tableName == "diary_tags" && it.fields().live }.map { it.named() }.sortedBy { it.name.lowercase() })
    }.flowOn(Dispatchers.Default)

    override suspend fun save(draft: DiaryDraft, defaultTitle: String): String = host.access { db -> db.withTransaction {
        require(draft.valid && defaultTitle.isNotBlank())
        val links = db.records().snapshots("diary_links").links(draft.id)
        val attachments = db.records().snapshots("media_attachments").owned(AttachmentOwnerType.DIARY, draft.id)
        storage.check(db.records().snapshot("diary_entries", draft.id), draft.revision, links + attachments)
        val tags = db.records().snapshots("diary_tags").filter { it.fields().live }.associate { it.entityId to it.fields().text("name")!! }
        val tagValues = draft.tags.map { DiaryTagReference(it.key.trim(), tags[it.key] ?: it.value.trim()) }
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }.distinctBy { it.key }
        val fields = buildJsonObject {
            put("title", draft.title.trim().ifEmpty { defaultTitle }); put("content", draft.content.trim()); put("mood", draft.mood.wire)
            put("occurred_at", draft.occurredAt); put("latitude", draft.latitude.json()); put("longitude", draft.longitude.json()); put("horizontal_accuracy", draft.accuracy.json())
            put("tags_json", JsonArray(tagValues.map { buildJsonObject { put("key", it.key); put("value", it.value) } }).toString())
        }
        storage.writer.saveInTransaction(db, listOf(storage.change("diary_entries", draft.id, fields)))
        val desired = draft.links.distinctBy { it.key }
        val changes = desired.map { link ->
            require(link.sourceId.isNotBlank() && link.title.isNotBlank() && DiarySource.entries.any { it.wire == link.sourceType })
            val id = links.firstOrNull { it.fields().live && it.link().key == link.key }?.entityId ?: UUID.randomUUID().toString()
            storage.change("diary_links", id, buildJsonObject {
                put("diary_id", draft.id); put("source_type", link.sourceType); put("source_id", link.sourceId)
                put("source_title", link.title); put("source_subtitle", link.subtitle.json()); put("source_icon", link.icon)
                put("source_occurred_at", link.occurredAt.json()); put("snapshot_json", link.snapshot.json())
            })
        } + links.filter { it.fields().live && it.link().key !in desired.map { link -> link.key } }
            .map { storage.change("diary_links", it.entityId, deleted = true) }
        storage.writer.saveInTransaction(db, changes)
        media.saveAttachmentsInTransaction(db, AttachmentOwner(AttachmentOwnerType.DIARY, draft.id), draft.attachments)
        draft.id
    } }
    override suspend fun delete(id: String, revision: String) = host.access { db -> db.withTransaction {
        storage.deleteOwner(db, AttachmentOwnerType.DIARY, id, revision, db.records().snapshots("diary_links").links(id))
    } }
    override suspend fun saveTag(draft: NamedRecordDraft) = storage.saveName("diary_tags", draft)
    override suspend fun deleteTag(id: String, revision: String) = storage.deleteName("diary_tags", id, revision)
    override fun sources(source: DiarySource): Flow<List<DiaryLink>> = if (source == DiarySource.REMINDER) {
        reminders.reminders.map { rows -> rows.map { diaryReminderSource(it, now(), zone(), context.resources) } }.flowOn(Dispatchers.Default)
    } else host.observe {
        it.records().observeTables(listOf(source.table, "item_categories"))
    }.map { rows -> rows.filter { it.tableName == source.table && it.fields().live }.map { row ->
        diarySource(source, row, rows.filter { it.tableName == "item_categories" && it.fields().live }.associate { it.entityId to it.fields().text("name")!! })
    }.sortedWith(compareByDescending<DiaryLink> { it.occurredAt }.thenBy { it.sourceId }) }.flowOn(Dispatchers.Default)
    private fun List<EntitySnapshot>.links(id: String) = filter { it.tableName == "diary_links" && it.fields().text("diary_id") == id }
    private fun EntitySnapshot.link(): DiaryLink = fields().let { DiaryLink(it.text("source_type")!!, it.text("source_id")!!,
        it.text("source_title")!!, it.text("source_subtitle"), it.text("source_icon")!!, it.long("source_occurred_at"), it.text("snapshot_json")) }
}
