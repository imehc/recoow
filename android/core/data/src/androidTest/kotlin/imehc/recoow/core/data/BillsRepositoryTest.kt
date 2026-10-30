package imehc.recoow.core.data

import android.graphics.Bitmap
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.backup.BackupPreferences
import imehc.recoow.core.backup.LocalRollbackFiles
import imehc.recoow.core.data.bills.RoomBillsRepository
import imehc.recoow.core.data.media.RoomMediaRepository
import imehc.recoow.core.data.records.ReceivedRecordStore
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.RecordChange
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BillsRepositoryTest {
    private lateinit var root: File
    private lateinit var host: DatabaseHost
    private lateinit var bills: RoomBillsRepository
    private lateinit var records: RoomRecordRepository
    private var clock = 1_793_347_200_000L
    private val scope = "bills-test"
    private val preferences = object : BackupPreferences {
        override suspend fun capture() = "{}"
        override suspend fun restore(json: String) = Unit
    }
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "bills-test-${UUID.randomUUID()}").apply { mkdirs() }
        host = newHost("local")
        bills = RoomBillsRepository(host, "test-device") { clock }
        records = RoomRecordRepository(host)
    }
    private fun newHost(name: String): DatabaseHost {
        val directory = File(root, name)
        return DatabaseHost(InstrumentationRegistry.getInstrumentation().targetContext, directory,
            LocalRollbackFiles(directory, File(root, "$name-rollback"), preferences, "test"))
    }
    @After fun cleanup() = runBlocking { host.close(); root.deleteRecursively(); Unit }
    private suspend fun account(id: String = "card") = bills.accounts.first().single { it.id == id }
    private suspend fun bill(id: String) = bills.bills.first().single { it.id == id }
    private suspend fun open(id: String = "card", balance: Long = 0) = bills.saveAccount(id, null, id, AccountKind.TRANSIT, balance, null)
    private fun draft(id: String = "bill", account: String? = "card", type: BillType = BillType.EXPENSE,
        original: Long = 10_000, paid: Long = 9_000) = BillDraft(id = id, title = id, occurredAt = clock,
        amounts = AmountInput.from(if (type == BillType.EXPENSE) BillAmounts(original, original - paid, paid) else BillAmounts(paid, 0, paid)),
        type = type, category = if (type == BillType.INCOME) "salary" else "dining", recharge = account != null, accountId = account)
    private suspend fun fields(table: String, id: String) = Json.parseToJsonElement(records.find(table, id)!!.snapshotJson).jsonObject
    private suspend fun changes() = host.access { db -> db.openHelper.writableDatabase.query("SELECT count(*) FROM local_changes").use { it.moveToFirst(); it.getLong(0) } }
    private suspend fun failure(type: Class<out Throwable>, action: suspend () -> Unit) {
        try { action(); fail("Expected ${type.simpleName}") } catch (error: Throwable) { if (!type.isInstance(error)) throw error }
    }

    @Test fun openingAndManualBalancesAreLedgerEntriesAndStaleFormsCannotOverwrite() = runBlocking {
        open(balance = 5_000)
        val opening = fields("stored_value_adjustments", "opening:card")
        assertEquals(5_000L, opening["amount_cents"]!!.jsonPrimitive.long)
        assertEquals("opening", opening["reason"]!!.jsonPrimitive.content)
        var old = account()
        bills.saveAccount(old.id, old.revision, "Metro", old.kind, old.balance, "note")
        assertEquals(1, records.observe("stored_value_adjustments").first().size)
        old = account()
        bills.saveAccount(old.id, old.revision, old.name, old.kind, 6_500, null)
        assertEquals(6_500L, account().balance)
        assertEquals(1_500L, records.observe("stored_value_adjustments").first().map { Json.parseToJsonElement(it.snapshotJson).jsonObject }
            .single { it["reason"]!!.jsonPrimitive.content == "manual" }["amount_cents"]!!.jsonPrimitive.long)
        val before = changes()
        failure(StaleBillException::class.java) { bills.saveAccount(old.id, old.revision, "Stale", old.kind, old.balance, null) }
        assertEquals(before, changes()); assertEquals("Metro", account().name)
    }

    @Test fun rechargeUsesFaceValueAndMoveRefundDeleteReverseOnlyTheirOwnEffects() = runBlocking {
        open(balance = 5_000); open("other")
        bills.save(draft())
        assertEquals(15_000L, account().balance)
        bills.save(draft("use", type = BillType.STORED_VALUE_USE, paid = 2_000))
        bills.save(draft("income", account = null, type = BillType.INCOME, paid = 1_000))
        assertEquals(13_000L, account().balance)
        bills.save(BillDraft.from(bill("bill")).copy(accountId = "other"))
        assertEquals(3_000L, account().balance); assertEquals(10_000L, account("other").balance)
        val moved = bill("bill")
        assertEquals(mapOf("other" to 10_000L), moved.effects)
        bills.refund(moved.id, moved.revision, "cancelled")
        assertEquals(0L, account("other").balance)
        failure(AccountInUseException::class.java) { val a = account("other"); bills.deleteAccount(a.id, a.revision) }
        val refunded = bill("bill")
        bills.save(BillDraft.from(refunded).copy(title = "Edited refund"))
        assertEquals(Settlement.REFUNDED, bill("bill").settlement)
        assertEquals("cancelled", bill("bill").refundReason)
        bills.delete("bill", bill("bill").revision)
        assertEquals(0L, account("other").balance)
        val other = account("other"); bills.deleteAccount(other.id, other.revision)
        bills.delete("use", bill("use").revision)
        assertEquals(5_000L, account().balance)
        assertEquals("{}", fields("bills", "use")["stored_value_effects"]!!.jsonPrimitive.content)
        assertNotEquals(JsonNull, fields("bills", "use")["deleted_at"])
    }

    @Test fun expiryDoesNotWriteAndDeletingExpiredRechargePreservesHistoricalEffects() = runBlocking {
        open(); bills.save(draft().copy(category = "groupBuy", validUntil = clock + 100))
        val before = records.find("bills", "bill")!!.snapshotJson
        val mutations = changes()
        clock += 101
        assertTrue(bill("bill").expired(clock)); assertEquals(0L, bill("bill").countedAmount(clock))
        assertEquals(before, records.find("bills", "bill")!!.snapshotJson); assertEquals(mutations, changes())
        assertEquals(10_000L, account().balance)
        failure(IllegalArgumentException::class.java) { bills.redeem("bill", bill("bill").revision) }
        failure(IllegalArgumentException::class.java) { bills.refund("bill", bill("bill").revision, null) }
        bills.delete("bill", bill("bill").revision)
        assertEquals(10_000L, account().balance)
        assertEquals("{\"card\":10000}", fields("bills", "bill")["stored_value_effects"]!!.jsonPrimitive.content)
    }

    @Test fun redemptionAndCopyLifecycleAreIndependentAndCancellingDraftWritesNothing() = runBlocking {
        bills.save(draft(account = null).copy(category = "groupBuy", validUntil = clock + 100))
        bills.redeem("bill", bill("bill").revision)
        val redeemed = bill("bill")
        assertEquals(clock, redeemed.redeemedAt)
        failure(IllegalArgumentException::class.java) { bills.refund(redeemed.id, redeemed.revision, null) }
        val before = changes()
        val copy = BillDraft.from(redeemed, duplicate = true, now = clock + 1)
        assertEquals(before, changes()); assertEquals(1, bills.bills.first().size)
        bills.save(copy)
        val saved = bill(copy.id)
        assertEquals(Settlement.ACTIVE, saved.settlement); assertNull(saved.redeemedAt); assertNull(saved.refundReason)
        bills.save(BillDraft.from(redeemed).copy(title = "Edited redeemed"))
        assertEquals(clock, bill("bill").redeemedAt)
    }

    @Test fun overflowAndStaleBillFailuresRollBackEveryWriteWhileNegativeBalancesRemainValid() = runBlocking {
        open(balance = Long.MAX_VALUE)
        val before = changes()
        failure(ArithmeticException::class.java) { bills.save(draft()) }
        assertEquals(before, changes()); assertTrue(bills.bills.first().isEmpty()); assertEquals(Long.MAX_VALUE, account().balance)
        val old = account(); bills.saveAccount(old.id, old.revision, old.name, old.kind, 0, null)
        bills.save(draft("use", type = BillType.STORED_VALUE_USE, paid = 2_000))
        assertEquals(-2_000L, account().balance)
        val use = bill("use")
        bills.save(BillDraft.from(use).copy(title = "Updated"))
        failure(StaleBillException::class.java) { bills.save(BillDraft.from(use).copy(title = "Stale")) }
        failure(StaleBillException::class.java) { bills.delete(use.id, use.revision) }
        assertEquals("Updated", bill("use").title); assertEquals(-2_000L, account().balance)
    }

    @Test fun utilityTextUnknownFieldsAndOutboxSurviveEdits() = runBlocking {
        ReceivedRecordStore(host).attach(SyncTarget(scope, "connection", "account", "generation"))
        bills.save(draft(account = null).copy(category = "utility", utilityNumber = "00001234", note = "meter"))
        host.access { db -> db.withTransaction {
            val row = db.records().snapshot("bills", "bill")!!
            db.records().put(row.copy(json = JsonObject(Json.parseToJsonElement(row.json).jsonObject + ("future_field" to JsonPrimitive("keep"))).toString()))
        } }
        bills.save(BillDraft.from(bill("bill")).copy(title = "Water"))
        assertEquals("00001234", bill("bill").utilityAccountNumber)
        assertEquals("keep", fields("bills", "bill")["future_field"]!!.jsonPrimitive.content)
        host.access { db ->
            val pending = db.records().pending(scope, "bills", "bill")!!
            val snapshot = Json.parseToJsonElement(pending.payloadJson).jsonObject
            assertEquals("keep", snapshot["future_field"]!!.jsonPrimitive.content)
            assertEquals("00001234", snapshot["utility_account_number"]!!.jsonPrimitive.content)
        }
    }

    @Test fun copiesPreservePendingAndLegacyImagesAndAssetReferences() = runBlocking {
        val file = File(root, "receipt.jpg")
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }; bitmap.recycle()
        bills.save(draft(account = null).copy(imageEdit = BillImageEdit.FILE, imageFile = file.path, hasImage = true))
        val reference = fields("bills", "bill")["_media"]!!.jsonObject["image_data"]!!
        val objectFile = MediaObjects(host.mediaDirectory).file(MediaReference.parse(reference))
        objectFile.delete()
        val pending = BillDraft.from(bill("bill"), duplicate = true)
        bills.save(pending)
        assertEquals(reference, fields("bills", pending.id)["_media"]!!.jsonObject["image_data"])
        val media = RoomMediaRepository(host, File(root, "media-cache"), "test-device")
        assertEquals(MediaAvailability.PENDING, media.resolve(MediaLocator(table = "bills", recordId = pending.id)).availability)
        host.access { db -> db.withTransaction {
            val row = db.records().snapshot("bills", "bill")!!
            db.records().put(row.copy(json = JsonObject(Json.parseToJsonElement(row.json).jsonObject - "_media").toString()))
        } }
        assertTrue(bill("bill").hasImage)
        val legacy = BillDraft.from(bill("bill"), duplicate = true)
        bills.save(legacy)
        assertArrayEquals(file.readBytes(), media.resolve(MediaLocator(table = "bills", recordId = legacy.id)).file!!.readBytes())
        val asset = media.createImage(PreparedMedia(file, "image/jpeg", 40, 30), "Receipt")
        bills.save(BillDraft.from(bill("bill")).copy(imageEdit = BillImageEdit.ASSET, imageAssetId = asset))
        bills.save(BillDraft.from(bill("bill"), duplicate = true))
        assertEquals(2, media.assets.first().single().references)
        failure(AssetInUseException::class.java) { media.delete(asset) }
    }

    @Test fun independentDeviceBillsAndReplayConvergeWithoutApplyingBalanceEffectsTwice() = runBlocking {
        open(balance = 5_000); bills.save(draft("recharge")); bills.save(draft("use", type = BillType.STORED_VALUE_USE, paid = 2_000))
        val target = newHost("received")
        try {
            val receiver = ReceivedRecordStore(target)
            receiver.attach(SyncTarget(scope, "connection", "account", "generation"))
            val snapshots = listOf("stored_value_accounts" to "card", "stored_value_adjustments" to "opening:card", "bills" to "recharge", "bills" to "use")
            val envelopes = snapshots.mapIndexed { index, (table, id) -> buildJsonObject {
                put("table", table); put("id", id); put("version", index + 1); put("dataVersion", EntityContracts.registry.entity(table).version)
                put("updatedAtMs", clock); put("deviceId", if (id == "use") "second-device" else "first-device"); put("deletedAtMs", JsonNull)
                put("snapshot", fields(table, id))
            }.toString() }
            val token = target.sessionToken()
            receiver.receivePage(scope, token, "ledger", envelopes.toString().toByteArray(), envelopes, 4)
            assertEquals(4, receiver.applyPending(scope, token))
            val projected = RoomBillsRepository(target, "received-device")
            assertEquals(13_000L, projected.accounts.first().single().balance)
            receiver.receivePage(scope, token, "ledger", envelopes.toString().toByteArray(), envelopes, 4)
            assertEquals(0, receiver.applyPending(scope, token))
            assertEquals(13_000L, projected.accounts.first().single().balance)
        } finally { target.close() }
    }
}
