package imehc.recoow.core.domain.bills

import java.io.Serializable
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class BillType(val wire: String) { EXPENSE("expense"), INCOME("income"), STORED_VALUE_USE("storedValueUse") }
enum class Settlement(val wire: String) { ACTIVE("active"), REDEEMED("redeemed"), REFUNDED("refunded") }
enum class PaymentMethod(val wire: String) { WECHAT("wechat"), ALIPAY("alipay"), DOUYIN("douyinPay"), UNION_PAY("unionPay"), BANK_CARD("bankCard"), CASH("cash"), CREDIT_CARD("creditCard"), OTHER("other") }
enum class AccountKind(val wire: String) { TRANSIT("transitCard"), MEAL("mealCard"), MEMBERSHIP("membershipCard"), GIFT("giftCard"), PHONE("phoneBalance"), OTHER("other") }
val expenseCategories = listOf("dining", "shopping", "transport", "groupBuy", "housing", "utility", "entertainment", "medical", "learning", "social", "other")
val incomeCategories = listOf("salary", "bonus", "investmentDividend", "appPayout", "partTime", "reimbursement", "refund", "other")

data class BillAmounts(val original: Long, val discount: Long, val final: Long) : Serializable {
    fun valid(type: BillType) = original > 0 && final in 0..original && discount == original - final &&
        (type == BillType.EXPENSE || final > 0 && original == final && discount == 0L)
}

data class BillRecord(val id: String, val revision: String, val title: String, val amounts: BillAmounts,
    val type: BillType, val category: String, val payment: PaymentMethod, val occurredAt: Long,
    val note: String? = null, val startLocation: String? = null, val endLocation: String? = null,
    val transportLines: String? = null, val utilityAccountNumber: String? = null,
    val settlement: Settlement = Settlement.ACTIVE, val validUntil: Long? = null, val redeemedAt: Long? = null,
    val refundReason: String? = null, val accountId: String? = null, val imageAssetId: String? = null,
    val hasImage: Boolean = false, val effects: Map<String, Long> = emptyMap(), val deleted: Boolean = false) {
    val isGroupBuy get() = type == BillType.EXPENSE && category == "groupBuy"
    fun expired(now: Long) = isGroupBuy && settlement == Settlement.ACTIVE && validUntil?.let { it < now } == true
    fun voided(now: Long) = settlement == Settlement.REFUNDED || expired(now)
    fun canRedeem(now: Long) = !deleted && isGroupBuy && settlement == Settlement.ACTIVE && !expired(now)
    fun canRefund(now: Long) = !deleted && settlement == Settlement.ACTIVE && !expired(now)
    fun countedAmount(now: Long) = if (deleted || voided(now)) 0 else amounts.final
    fun countedDiscount(now: Long) = if (deleted || voided(now)) 0 else amounts.discount
    fun balanceDelta(now: Long): Long = if (accountId == null || deleted || voided(now)) 0 else when (type) {
        BillType.EXPENSE -> amounts.original
        BillType.STORED_VALUE_USE -> -amounts.final
        BillType.INCOME -> 0
    }
}

data class StoredValueAccount(val id: String, val revision: String, val name: String, val kind: AccountKind,
    val balance: Long, val note: String? = null, val references: Int = 0)

enum class BillImageEdit { KEEP, CLEAR, ASSET, FILE }
data class BillDraft(val id: String = UUID.randomUUID().toString(), val revision: String? = null,
    val title: String = "", val amounts: AmountInput = AmountInput(), val type: BillType = BillType.EXPENSE,
    val category: String = "dining", val payment: PaymentMethod = PaymentMethod.WECHAT,
    val occurredAt: Long = System.currentTimeMillis(), val note: String = "", val start: String = "", val end: String = "",
    val lines: String = "", val utilityNumber: String = "", val validUntil: Long? = null,
    val recharge: Boolean = false, val accountId: String? = null, val imageEdit: BillImageEdit = BillImageEdit.KEEP,
    val imageAssetId: String? = null, val imageFile: String? = null, val imageSourceId: String? = null,
    val hasImage: Boolean = false) : Serializable {
    val requiresAccount get() = type == BillType.STORED_VALUE_USE || type == BillType.EXPENSE && recharge
    val isTransport get() = type != BillType.INCOME && category == "transport"
    val isUtility get() = type != BillType.INCOME && category == "utility"
    val isGroupBuy get() = type == BillType.EXPENSE && category == "groupBuy"
    val valid get() = title.trim().isNotEmpty() && amounts.values(type) != null &&
        (!requiresAccount || accountId != null) && (!isGroupBuy || validUntil != null)
    fun withType(value: BillType) = copy(type = value,
        category = if ((value == BillType.INCOME) != (type == BillType.INCOME)) {
            if (value == BillType.INCOME) "salary" else "dining"
        } else category,
        amounts = amounts.withType(value), recharge = value == BillType.EXPENSE && recharge,
        accountId = if (value == BillType.INCOME) null else accountId)
    companion object {
        fun from(record: BillRecord, duplicate: Boolean = false, now: Long = System.currentTimeMillis()) = BillDraft(
            id = if (duplicate) UUID.randomUUID().toString() else record.id, revision = if (duplicate) null else record.revision,
            title = record.title, amounts = AmountInput.from(record.amounts), type = record.type,
            category = record.category, payment = record.payment, occurredAt = if (duplicate) now else record.occurredAt,
            note = record.note.orEmpty(), start = record.startLocation.orEmpty(), end = record.endLocation.orEmpty(),
            lines = record.transportLines.orEmpty(), utilityNumber = record.utilityAccountNumber.orEmpty(), validUntil = record.validUntil,
            recharge = record.type == BillType.EXPENSE && record.accountId != null, accountId = record.accountId,
            imageAssetId = record.imageAssetId, imageSourceId = record.id, hasImage = record.hasImage)
    }
}

data class BillFilter(val type: BillType? = null, val category: String? = null, val incomeCategory: String? = null,
    val payment: PaymentMethod? = null, val query: String = "") : Serializable {
    fun withType(value: BillType?) = copy(type = value, category = if (value == BillType.INCOME) null else category,
        incomeCategory = if (value != BillType.INCOME) null else incomeCategory)
    fun matches(bill: BillRecord, searchable: String): Boolean = (type == null || type == bill.type) &&
        (category == null || bill.type != BillType.INCOME && category == bill.category) &&
        (incomeCategory == null || bill.type == BillType.INCOME && incomeCategory == bill.category) &&
        (payment == null || payment == bill.payment) && searchable.contains(query.trim(), ignoreCase = true)
}

data class BillSummary(val todayExpense: BigInteger, val todayIncome: BigInteger, val todayDiscount: BigInteger,
    val todayRecharge: BigInteger, val todayUse: BigInteger, val monthExpense: BigInteger,
    val monthIncome: BigInteger, val monthDiscount: BigInteger, val monthUse: BigInteger) {
    companion object {
        fun calculate(bills: List<BillRecord>, now: Long, zone: ZoneId): BillSummary {
            val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val today = bills.filter { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() == date }
            val month = bills.filter { val day = Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate(); day.year == date.year && day.month == date.month }
            fun List<BillRecord>.sum(type: BillType, discount: Boolean = false) = filter { it.type == type }.fold(BigInteger.ZERO) { sum, bill ->
                sum + BigInteger.valueOf(if (discount) bill.countedDiscount(now) else bill.countedAmount(now))
            }
            return BillSummary(today.sum(BillType.EXPENSE), today.sum(BillType.INCOME), today.sum(BillType.EXPENSE, true),
                today.filter { it.accountId != null }.sum(BillType.EXPENSE), today.sum(BillType.STORED_VALUE_USE),
                month.sum(BillType.EXPENSE), month.sum(BillType.INCOME), month.sum(BillType.EXPENSE, true), month.sum(BillType.STORED_VALUE_USE))
        }
    }
}

class StaleBillException : IllegalStateException("Record changed; reload before saving")
class AccountInUseException : IllegalStateException("Account is referenced by live bills")
interface BillsRepository {
    val bills: Flow<List<BillRecord>>
    val accounts: Flow<List<StoredValueAccount>>
    suspend fun save(draft: BillDraft): String
    suspend fun delete(id: String, revision: String)
    suspend fun redeem(id: String, revision: String)
    suspend fun refund(id: String, revision: String, reason: String?)
    suspend fun saveAccount(id: String, revision: String?, name: String, kind: AccountKind, balance: Long, note: String?): String
    suspend fun deleteAccount(id: String, revision: String)
}
