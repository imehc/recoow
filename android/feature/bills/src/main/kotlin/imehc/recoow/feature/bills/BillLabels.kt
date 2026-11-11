package imehc.recoow.feature.bills

import android.content.res.Resources
import imehc.recoow.core.domain.bills.*
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

internal fun labelId(wire: String): Int = when (wire) {
    "expense" -> R.string.expense
    "income" -> R.string.income
    "storedValueUse" -> R.string.stored_value_use
    "dining" -> R.string.dining
    "shopping" -> R.string.shopping
    "transport" -> R.string.transport
    "groupBuy" -> R.string.group_buy
    "housing" -> R.string.housing
    "utility" -> R.string.utility
    "entertainment" -> R.string.entertainment
    "medical" -> R.string.medical
    "learning" -> R.string.learning
    "social" -> R.string.social
    "other" -> R.string.other
    "salary" -> R.string.salary
    "bonus" -> R.string.bonus
    "investmentDividend" -> R.string.investment_dividend
    "appPayout" -> R.string.app_payout
    "partTime" -> R.string.part_time
    "reimbursement" -> R.string.reimbursement
    "refund" -> R.string.refund
    "wechat" -> R.string.wechat
    "alipay" -> R.string.alipay
    "douyinPay" -> R.string.douyin_pay
    "unionPay" -> R.string.union_pay
    "bankCard" -> R.string.bank_card
    "cash" -> R.string.cash
    "creditCard" -> R.string.credit_card
    "transitCard" -> R.string.transit_card
    "mealCard" -> R.string.meal_card
    "membershipCard" -> R.string.membership_card
    "giftCard" -> R.string.gift_card
    "phoneBalance" -> R.string.phone_balance
    else -> R.string.other
}
internal fun categoryLabel(resources: Resources, bill: BillRecord) = resources.getString(if (bill.type == BillType.INCOME && bill.category == "other") R.string.other_income else labelId(bill.category))
internal fun searchable(resources: Resources, bill: BillRecord, accounts: List<StoredValueAccount>) = listOfNotNull(
    bill.title, resources.getString(labelId(bill.type.wire)), categoryLabel(resources, bill), resources.getString(labelId(bill.payment.wire)),
    accounts.firstOrNull { it.id == bill.accountId }?.name, bill.startLocation, bill.endLocation, bill.transportLines, bill.note).joinToString(" ")
internal fun money(cents: Long, locale: Locale) = money(BigInteger.valueOf(cents), locale)
internal fun money(cents: BigInteger, locale: Locale): String = NumberFormat.getCurrencyInstance(locale).apply {
    // 格式随应用语言，货币随设备地区，与 iOS 一致。
    currency = runCatching { Currency.getInstance(Resources.getSystem().configuration.locales[0]) }.getOrNull() ?: Currency.getInstance("CNY")
    minimumFractionDigits = 2; maximumFractionDigits = 2
}.format(BigDecimal(cents, 2))
