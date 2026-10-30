package imehc.recoow.core.data.journal

import android.graphics.BitmapFactory
import androidx.room.withTransaction
import imehc.recoow.core.database.*
import imehc.recoow.core.domain.items.*
import imehc.recoow.core.domain.records.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class RoomItemsRepository(private val host: DatabaseHost, deviceId: String, now: () -> Long = System::currentTimeMillis) : ItemsRepository {
    private val storage = JournalStorage(host, deviceId, now)
    override val snapshot = host.observe { db -> combine(db.records().observeTables(listOf("stored_items", "item_categories")), db.records().observeItemsWithInlineImages()) { rows, images ->
        ItemsSnapshot(rows.filter { it.tableName == "stored_items" && it.fields().live }.map { row -> val f = row.fields()
            StoredItem(row.entityId, revision(listOf(row)), f.text("title")!!, f.text("location")!!, f.text("category_id"), f.text("note"), f.text("tags"), f.text("search_keywords"),
                f.text("image_asset_id"), row.entityId in images || f.text("image_asset_id") != null || f["_media"]?.jsonObject?.get("image_data")?.let { it != JsonNull } == true, f.long("updated_at")!!)
        }.sortedWith(compareByDescending<StoredItem> { it.updatedAt }.thenBy { it.id }),
            rows.filter { it.tableName == "item_categories" && it.fields().live }.map { it.named() }.sortedBy { it.name.lowercase() })
    } }.flowOn(Dispatchers.Default)
    override suspend fun save(draft: ItemDraft): String = host.access { db -> db.withTransaction {
        val prior = db.records().snapshot("stored_items", draft.id)
        storage.check(prior, draft.revision); require(draft.valid)
        draft.categoryId?.let { if (it != prior?.fields()?.text("category_id")) require(db.records().snapshot("item_categories", it)?.fields()?.live == true) }
        val binary: Map<String, ByteArray?> = if (!draft.imageChanged) emptyMap() else {
            require(draft.imageContent == null || draft.imageAssetId == null)
            draft.imageAssetId?.let { require(db.records().snapshot("media_assets", it)?.fields()?.live == true) }
            val content = draft.imageContent
            val bytes = content?.let {
                require(it.file.length() in 1..16L * 1024 * 1024 && it.mimeType == "image/jpeg")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(it.file.path, bounds)
                require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048 && bounds.outMimeType == "image/jpeg")
                it.file.readBytes()
            }
            mapOf("image_data" to bytes)
        }
        storage.writer.saveInTransaction(db, listOf(storage.change("stored_items", draft.id, buildJsonObject {
            put("title", draft.title.trim()); put("location", draft.location.trim()); put("category_id", draft.categoryId.json())
            put("note", draft.note.normalized().json()); put("tags", draft.tags.normalized().json()); put("search_keywords", draft.keywords.normalized().json())
            if (draft.imageChanged || prior == null) put("image_asset_id", draft.imageAssetId.json())
        }, binary = binary)))
        draft.id
    } }
    override suspend fun delete(id: String, revision: String) = host.access { db -> db.withTransaction {
        storage.check(db.records().snapshot("stored_items", id), revision)
        storage.writer.saveInTransaction(db, listOf(storage.change("stored_items", id, deleted = true)))
    } }
    override suspend fun saveCategory(draft: NamedRecordDraft) = storage.saveName("item_categories", draft)
    override suspend fun deleteCategory(id: String, revision: String) = storage.deleteName("item_categories", id, revision)
}
