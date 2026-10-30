package imehc.recoow.core.data.bills

import android.graphics.BitmapFactory
import androidx.room.withTransaction
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.RecoowDatabase
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.records.RecordChange
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class RoomBillsRepository(private val host: DatabaseHost, private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis) : BillsRepository {
    private val writer = RoomRecordRepository(host)
    override val bills = host.observe { db -> combine(db.records().observe("bills"), db.records().observeBillsWithInlineImages()) { rows, images ->
        val inline = images.toSet()
        rows.map { it.bill(it.entityId in inline) }.filterNot { it.deleted }
            .sortedWith(compareByDescending<BillRecord> { it.occurredAt }.thenByDescending { it.id })
    } }.flowOn(Dispatchers.Default)
    override val accounts = host.observe { db -> combine(db.records().observe("stored_value_accounts"), db.records().observe("bills")) { accounts, bills ->
        val references = bills.map { it.fields() }.filter { it.live }.mapNotNull { it.text("stored_value_account_id") }.groupingBy { it }.eachCount()
        accounts.filter { it.fields().live }.map { it.account(references[it.entityId] ?: 0) }.sortedWith(compareBy<StoredValueAccount> { it.name }.thenBy { it.id })
    } }.flowOn(Dispatchers.Default)

    override suspend fun save(draft: BillDraft): String = host.access { db -> db.withTransaction {
        require(draft.valid)
        require(draft.category in if (draft.type == BillType.INCOME) incomeCategories else expenseCategories)
        val previous = current(db, "bills", draft.id, draft.revision)?.bill()
        val account = draft.accountId.takeIf { draft.requiresAccount }
        account?.let { require(row(db, "stored_value_accounts", it)?.fields()?.live == true) { "Missing account" } }
        val imageId = when (draft.imageEdit) {
            BillImageEdit.KEEP, BillImageEdit.ASSET -> draft.imageAssetId
            else -> null
        }
        if (draft.imageEdit == BillImageEdit.ASSET) require(imageId != null)
        imageId?.let { require(row(db, "media_assets", it)?.fields()?.live == true) { "Missing image asset" } }
        val record = BillRecord(draft.id, "", draft.title.trim(), requireNotNull(draft.amounts.values(draft.type)),
            draft.type, draft.category, draft.payment, draft.occurredAt, draft.note.trimmed(),
            draft.start.trimmed().takeIf { draft.isTransport }, draft.end.trimmed().takeIf { draft.isTransport },
            draft.lines.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").trimmed().takeIf { draft.isTransport },
            draft.utilityNumber.trimmed().takeIf { draft.isUtility }, previous?.settlement ?: Settlement.ACTIVE,
            draft.validUntil.takeIf { draft.isGroupBuy }, previous?.redeemedAt, previous?.refundReason, account, imageId)
        val binary = when (draft.imageEdit) {
            BillImageEdit.CLEAR, BillImageEdit.ASSET -> mapOf("image_data" to null)
            BillImageEdit.FILE -> {
                val file = File(requireNotNull(draft.imageFile)); require(file.length() in 1..16L * 1024 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048 && bounds.outMimeType == "image/jpeg")
                mapOf("image_data" to file.readBytes())
            }
            BillImageEdit.KEEP -> emptyMap()
        }
        val inherited = if (previous == null && draft.imageEdit == BillImageEdit.KEEP && imageId == null && draft.hasImage) {
            val source = requireNotNull(row(db, "bills", requireNotNull(draft.imageSourceId)))
            require(source.fields().live) { "Copy source was deleted" }
            var ref = source.fields()["_media"]?.jsonObject?.get("image_data")
            if (ref == null) {
                val temporary = File.createTempFile("bill-copy-", ".tmp", host.directory)
                try {
                    val size = PhysicalRecords.copyBinary(db.openHelper.writableDatabase, "bills", source.entityId, "image_data", temporary)
                    if (size != null && size > 0) ref = MediaObjects(host.mediaDirectory).capture(temporary, "image/jpeg").json()
                } finally { temporary.delete() }
            }
            ref?.let { mapOf(draft.id to buildJsonObject { put("image_data", it) }) }.orEmpty()
        } else emptyMap()
        persist(db, previous, record, binary, inherited)
        draft.id
    } }

    override suspend fun delete(id: String, revision: String) = mutateBill(id, revision) { it.copy(deleted = true) }
    override suspend fun redeem(id: String, revision: String) = mutateBill(id, revision) {
        require(it.canRedeem(now())) { "Cannot redeem this bill" }; it.copy(settlement = Settlement.REDEEMED, redeemedAt = now())
    }
    override suspend fun refund(id: String, revision: String, reason: String?) = mutateBill(id, revision) {
        require(it.canRefund(now())) { "Cannot refund this bill" }; it.copy(settlement = Settlement.REFUNDED, refundReason = reason?.trimmed())
    }
    private suspend fun mutateBill(id: String, revision: String, transform: (BillRecord) -> BillRecord) = host.access { db -> db.withTransaction {
        val previous = requireNotNull(current(db, "bills", id, revision)).bill()
        persist(db, previous, transform(previous))
    } }

    private suspend fun persist(db: RecoowDatabase, previous: BillRecord?, record: BillRecord,
        binary: Map<String, ByteArray?> = emptyMap(), inherited: Map<String, JsonObject> = emptyMap()) {
        val timestamp = now()
        // Match iOS's cumulative ledger: add only this operation's delta to the stored historical map.
        // In particular, an already-expired purchase has zero current delta but retains its past effect.
        val effects = previous?.effects.orEmpty().toMutableMap()
        val deltas = mutableMapOf<String, Long>()
        fun add(map: MutableMap<String, Long>, id: String, value: Long) {
            val result = Math.addExact(map[id] ?: 0, value)
            if (result == 0L) map.remove(id) else map[id] = result
        }
        previous?.accountId?.let { add(deltas, it, Math.negateExact(previous.balanceDelta(timestamp))) }
        record.accountId?.let { add(deltas, it, record.balanceDelta(timestamp)) }
        for ((id, amount) in deltas) if (row(db, "stored_value_accounts", id)?.fields()?.live == true) add(effects, id, amount)
        val fields = buildJsonObject {
            put("title", record.title); put("original_amount_cents", record.amounts.original)
            put("discount_amount_cents", record.amounts.discount); put("final_amount_cents", record.amounts.final)
            put("transaction_type", record.type.wire); put("category", record.category); put("payment_method", record.payment.wire)
            put("occurred_at", record.occurredAt); put("note", record.note.json()); put("start_location", record.startLocation.json())
            put("end_location", record.endLocation.json()); put("transport_lines", record.transportLines.json())
            put("utility_account_number", record.utilityAccountNumber.json()); put("settlement_status", record.settlement.wire)
            put("group_buy_valid_until", record.validUntil?.let(::JsonPrimitive) ?: JsonNull)
            put("redeemed_at", record.redeemedAt?.let(::JsonPrimitive) ?: JsonNull); put("refund_reason", record.refundReason.json())
            put("stored_value_account_id", record.accountId.json()); put("image_asset_id", record.imageAssetId.json())
            put("stored_value_effects", buildJsonObject { effects.toSortedMap().forEach { (id, amount) -> put(id, amount) } }.toString())
        }
        writer.saveInTransaction(db, listOf(RecordChange("bills", record.id, fields.toString(), timestamp, deviceId, record.deleted, binary)), inherited)
    }

    override suspend fun saveAccount(id: String, revision: String?, name: String, kind: AccountKind, balance: Long, note: String?): String = host.access { db -> db.withTransaction {
        require(name.isNotBlank() && balance >= 0)
        val previous = current(db, "stored_value_accounts", id, revision)?.account()
        val adjustment = Math.subtractExact(balance, previous?.balance ?: 0)
        val changes = mutableListOf(change("stored_value_accounts", id, buildJsonObject {
            put("name", name.trim()); put("kind", kind.wire); put("note", note?.trimmed().json())
        }))
        if (previous == null || adjustment != 0L) changes += change("stored_value_adjustments", if (previous == null) "opening:$id" else UUID.randomUUID().toString(), buildJsonObject {
            put("account_id", id); put("amount_cents", adjustment); put("reason", if (previous == null) "opening" else "manual")
        })
        writer.saveInTransaction(db, changes)
        id
    } }
    override suspend fun deleteAccount(id: String, revision: String) = host.access { db -> db.withTransaction {
        current(db, "stored_value_accounts", id, revision)
        val count = db.openHelper.writableDatabase.query("SELECT count(*) FROM bills WHERE deleted_at IS NULL AND stored_value_account_id = ?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
        if (count > 0) throw AccountInUseException()
        writer.saveInTransaction(db, listOf(change("stored_value_accounts", id, buildJsonObject {}, deleted = true)))
    } }

    private fun change(table: String, id: String, fields: JsonObject, deleted: Boolean = false) = RecordChange(table, id, fields.toString(), now(), deviceId, deleted)
    private suspend fun row(db: RecoowDatabase, table: String, id: String) = db.records().snapshot(table, id)
    private suspend fun current(db: RecoowDatabase, table: String, id: String, revision: String?): EntitySnapshot? {
        val row = row(db, table, id)
        if ((row == null) != (revision == null) || row != null && (row.revision() != revision || !row.fields().live)) throw StaleBillException()
        return row
    }
    private fun EntitySnapshot.fields() = Json.parseToJsonElement(json).jsonObject
    private fun EntitySnapshot.revision() = MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun EntitySnapshot.account(references: Int = 0): StoredValueAccount = fields().let { f ->
        StoredValueAccount(entityId, revision(), f.text("name")!!, AccountKind.entries.firstOrNull { it.wire == f.text("kind") } ?: AccountKind.OTHER,
            f.long("balance_cents") ?: 0, f.text("note"), references)
    }
    private fun EntitySnapshot.bill(inline: Boolean = false): BillRecord = fields().let { f ->
        BillRecord(entityId, revision(), f.text("title")!!,
            BillAmounts(f.long("original_amount_cents")!!, f.long("discount_amount_cents")!!, f.long("final_amount_cents")!!),
            BillType.entries.firstOrNull { it.wire == f.text("transaction_type") } ?: BillType.EXPENSE,
            f.text("category")!!, PaymentMethod.entries.firstOrNull { it.wire == f.text("payment_method") } ?: PaymentMethod.OTHER,
            f.long("occurred_at")!!, f.text("note"), f.text("start_location"), f.text("end_location"), f.text("transport_lines"),
            f.text("utility_account_number"), Settlement.entries.firstOrNull { it.wire == f.text("settlement_status") } ?: Settlement.ACTIVE,
            f.long("group_buy_valid_until"), f.long("redeemed_at"), f.text("refund_reason"), f.text("stored_value_account_id"), f.text("image_asset_id"),
            inline || f.text("image_asset_id") != null || f["_media"]?.jsonObject?.get("image_data")?.let { it != JsonNull } == true,
            f.text("stored_value_effects")?.let { Json.parseToJsonElement(it).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.long } }.orEmpty(), !f.live)
    }
    private val JsonObject.live get() = this["deleted_at"] == JsonNull
    private fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(name: String) = this[name]?.jsonPrimitive?.longOrNull
    private fun String.trimmed() = trim().takeIf { it.isNotEmpty() }
    private fun String?.json() = this?.let(::JsonPrimitive) ?: JsonNull
}
