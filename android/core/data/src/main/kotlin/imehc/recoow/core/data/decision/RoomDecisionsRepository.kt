package imehc.recoow.core.data.decision

import android.graphics.BitmapFactory
import androidx.room.withTransaction
import imehc.recoow.core.data.journal.*
import imehc.recoow.core.database.*
import imehc.recoow.core.domain.decision.*
import java.io.File
import java.math.BigInteger
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class RoomDecisionsRepository(private val host: DatabaseHost, deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val ticket: (BigInteger) -> BigInteger = { total ->
        val random = SecureRandom(); var value: BigInteger
        do { value = BigInteger(total.bitLength(), random) } while (value >= total)
        value
    }) : DecisionsRepository {
    private val storage = JournalStorage(host, deviceId, now)
    private val tables = listOf("decision_collections", "decision_options", "decision_choice_records")
    override val snapshot = host.observe { db -> combine(db.records().observeTables(tables), db.records().observeDecisionInlineImages()) { rows, images ->
        decode(rows, images.toSet())
    } }.flowOn(Dispatchers.Default)
    private fun decode(rows: List<EntitySnapshot>, images: Set<String>) = DecisionSnapshot(
        rows.liveRows("decision_collections").map { r -> val f = r.fields(); DecisionCollection(r.entityId, revision(listOf(r)), f.text("title")!!, f.text("note")) },
        rows.liveRows("decision_options").map { r -> option(r, images.contains("option:${r.entityId}")) },
        rows.liveRows("decision_choice_records").map { r -> val f = r.fields(); DecisionChoice(r.entityId, revision(listOf(r)), f.text("collection_id")!!,
            f.text("collection_title")!!, f.text("option_id")!!, f.text("option_title")!!, f.text("option_detail"), f.text("option_custom_info"), f.text("option_image_asset_id"),
            images.contains("choice:${r.entityId}") || f.hasImage("option_image_data", "option_image_asset_id"), f.long("selected_at")!!) }.sortedWith(compareByDescending<DecisionChoice> { it.selectedAt }.thenBy { it.id }))
    private fun List<EntitySnapshot>.liveRows(table: String) = filter { it.tableName == table && it.fields().live }.sortedWith(compareByDescending<EntitySnapshot> { it.fields().long("updated_at") }.thenBy { it.entityId })
    private fun JsonObject.hasImage(field: String = "image_data", asset: String = "image_asset_id") = text(asset) != null || this["_media"]?.jsonObject?.get(field)?.let { it != JsonNull } == true
    private fun option(r: EntitySnapshot, inline: Boolean = false): DecisionOption = r.fields().let { f ->
        DecisionOption(r.entityId, revision(listOf(r)), f.text("collection_id")!!, f.text("title")!!, f.text("detail"), f.text("custom_info"), f.long("weight")!!,
            f["is_enabled"]!!.jsonPrimitive.boolean, f.text("image_asset_id"), inline || f.hasImage())
    }
    override suspend fun saveCollection(draft: CollectionDraft): String = host.access { db -> db.withTransaction {
        require(draft.title.isNotBlank()); storage.check(db.records().snapshot("decision_collections", draft.id), draft.revision)
        storage.writer.saveInTransaction(db, listOf(storage.change("decision_collections", draft.id, buildJsonObject {
            put("title", draft.title.trim()); put("note", draft.note.normalized().json())
        }))); draft.id
    } }
    override suspend fun deleteCollection(id: String, revision: String) = host.access { db -> db.withTransaction {
        storage.check(db.records().snapshot("decision_collections", id), revision)
        val options = db.records().snapshots("decision_options").filter { it.fields().live && it.fields().text("collection_id") == id }
        storage.writer.saveInTransaction(db, options.map { storage.change("decision_options", it.entityId, deleted = true) } + storage.change("decision_collections", id, deleted = true))
    } }
    override suspend fun saveOption(draft: OptionDraft): String = host.access { db -> db.withTransaction {
        val old = db.records().snapshot("decision_options", draft.id)
        storage.check(old, draft.revision); require(draft.valid)
        require(db.records().snapshot("decision_collections", draft.collectionId)?.fields()?.live == true)
        require(old == null || old.fields().text("collection_id") == draft.collectionId)
        val weight = requireNotNull(draft.weight.toLongOrNull()).coerceAtLeast(1)
        require(weight in 1..100 || weight == old?.fields()?.long("weight"))
        val binary = if (!draft.imageChanged) emptyMap() else {
            require(draft.imageContent == null || draft.imageAssetId == null)
            draft.imageAssetId?.let { require(db.records().snapshot("media_assets", it)?.fields()?.live == true) }
            val bytes = draft.imageContent?.let { content ->
                require(content.file.length() in 1..16L * 1024 * 1024 && content.mimeType == "image/jpeg")
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(content.file.path, b)
                require(b.outWidth in 1..2048 && b.outHeight in 1..2048 && b.outMimeType == "image/jpeg"); content.file.readBytes()
            }; mapOf("image_data" to bytes)
        }
        storage.writer.saveInTransaction(db, listOf(storage.change("decision_options", draft.id, buildJsonObject {
            put("collection_id", draft.collectionId); put("title", draft.title.trim()); put("detail", draft.detail.normalized().json()); put("custom_info", draft.customInfo.normalized().json())
            put("weight", weight); put("is_enabled", draft.enabled)
            if (draft.imageChanged || old == null) put("image_asset_id", draft.imageAssetId.json())
        }, binary = binary))); draft.id
    } }
    override suspend fun choose(collectionId: String): String = host.access { db -> db.withTransaction {
        val collection = requireNotNull(db.records().snapshot("decision_collections", collectionId)).fields(); require(collection.live)
        val rows = db.records().snapshots("decision_options").liveRows("decision_options").filter { it.fields().text("collection_id") == collectionId }
        val selected = weightedChoice(rows.map { option(it) }, ticket)
        val source = rows.single { it.entityId == selected.id }.fields()
        val id = UUID.randomUUID().toString()
        var image = source["_media"]?.jsonObject?.get("image_data")
        if (image == null) {
            val temporary = File.createTempFile("choice-snapshot-", ".jpg", host.directory)
            try {
                val bytes = PhysicalRecords.copyBinary(db.openHelper.writableDatabase, "decision_options", selected.id, "image_data", temporary)
                if (bytes != null && bytes > 0) image = MediaObjects(host.mediaDirectory).capture(temporary, "image/jpeg").json()
            } finally { temporary.delete() }
        }
        val inherited = image?.let { mapOf(id to buildJsonObject { put("option_image_data", it) }) }.orEmpty()
        storage.writer.saveInTransaction(db, listOf(storage.change("decision_choice_records", id, buildJsonObject {
            put("collection_id", collectionId); put("collection_title", collection.text("title")!!); put("option_id", selected.id); put("option_title", selected.title)
            put("option_detail", selected.detail.json()); put("option_custom_info", selected.customInfo.json()); put("option_image_asset_id", selected.imageAssetId.json()); put("selected_at", now())
        })), inherited); id
    } }
    override suspend fun deleteOption(id: String, revision: String) = delete("decision_options", id, revision)
    override suspend fun deleteChoice(id: String, revision: String) = delete("decision_choice_records", id, revision)
    private suspend fun delete(table: String, id: String, revision: String) = host.access { db -> db.withTransaction {
        storage.check(db.records().snapshot(table, id), revision); storage.writer.saveInTransaction(db, listOf(storage.change(table, id, deleted = true)))
    } }
}
