package imehc.recoow.core.designsystem

import android.content.res.Resources
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import imehc.recoow.core.domain.bills.BillRecord
import imehc.recoow.core.domain.bills.BillType
import imehc.recoow.core.domain.navigation.ToolRoute

private val billSourceLabels = mapOf(
    "dining" to R.string.record_bill_dining,
    "shopping" to R.string.record_bill_shopping,
    "transport" to R.string.record_bill_transport,
    "groupBuy" to R.string.record_bill_group_buy,
    "housing" to R.string.record_bill_housing,
    "utility" to R.string.record_bill_utility,
    "entertainment" to R.string.record_bill_entertainment,
    "medical" to R.string.record_bill_medical,
    "learning" to R.string.record_bill_learning,
    "social" to R.string.record_bill_social,
    "other" to R.string.record_bill_other,
    "salary" to R.string.record_bill_salary,
    "bonus" to R.string.record_bill_bonus,
    "investmentDividend" to R.string.record_bill_investment_dividend,
    "appPayout" to R.string.record_bill_app_payout,
    "partTime" to R.string.record_bill_part_time,
    "reimbursement" to R.string.record_bill_reimbursement,
    "refund" to R.string.record_bill_refund,
    "wechat" to R.string.record_bill_wechat,
    "alipay" to R.string.record_bill_alipay,
    "douyinPay" to R.string.record_bill_douyin_pay,
    "unionPay" to R.string.record_bill_union_pay,
    "bankCard" to R.string.record_bill_bank_card,
    "cash" to R.string.record_bill_cash,
    "creditCard" to R.string.record_bill_credit_card,
)

/** 日记快照和饮食关联共用账单分类、支付方式的显示名称。 */
fun recordBillSourceLabel(wire: String, resources: Resources): String =
    billSourceLabels[wire]?.let(resources::getString) ?: wire

fun recordBillTypeLabel(type: BillType?, resources: Resources): String = resources.getString(when (type) {
    BillType.EXPENSE -> R.string.record_expense
    BillType.INCOME -> R.string.record_income
    BillType.STORED_VALUE_USE -> R.string.record_stored_value
    null -> R.string.record_bill
})

fun recordBillMetadata(bill: BillRecord, resources: Resources): String = listOf(
    recordBillTypeLabel(bill.type, resources),
    recordBillSourceLabel(bill.category, resources),
    recordBillSourceLabel(bill.payment.wire, resources),
).joinToString(" · ")

/** 分类图标与 iOS 采用相同语义，保留 Android 的统一线条风格。 */
fun recordBillIcon(type: BillType, category: String): Int = if (type == BillType.STORED_VALUE_USE) R.drawable.ic_tool_bills else when (category) {
    "dining" -> R.drawable.ic_tool_food
    "shopping" -> R.drawable.ic_bill_shopping
    "transport" -> R.drawable.ic_bill_transport
    "groupBuy" -> R.drawable.ic_bill_ticket
    "housing" -> R.drawable.ic_bill_home
    "utility" -> R.drawable.ic_bill_utility
    "entertainment" -> R.drawable.ic_bill_game
    "medical" -> R.drawable.ic_bill_medical
    "learning" -> R.drawable.ic_bill_book
    "social", "bonus" -> R.drawable.ic_bill_gift
    "salary" -> R.drawable.ic_bill_work
    "investmentDividend" -> R.drawable.ic_bill_chart
    "appPayout" -> R.drawable.ic_bill_app
    "partTime" -> R.drawable.ic_clock
    "reimbursement" -> R.drawable.ic_bill_receipt
    "refund" -> R.drawable.ic_record_back
    else -> R.drawable.ic_more
}

@Composable
fun recordBillTint(type: BillType): Color = when (type) {
    BillType.EXPENSE -> RecoowToolIcons.tint(ToolRoute.ITEMS)
    BillType.INCOME -> MaterialTheme.colorScheme.tertiary
    BillType.STORED_VALUE_USE -> RecoowPalette.warningColor(MaterialTheme.colorScheme.background.luminance() < 0.5f)
}

/** 各页面共用账单关联行及固定操作区；领域模型由调用方解析为显示值。 */
@Composable
fun RecordLinkedBillRow(title: String, amount: String?, type: BillType?, modifier: Modifier = Modifier,
    subtitle: String? = null, date: String? = null, onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null) {
    RecordMetadataRow(title, modifier, subtitle = subtitle, date = date, onClick = onClick,
        icon = { Icon(painterResource(R.drawable.ic_tool_bills), null,
            tint = RecoowToolIcons.tint(ToolRoute.BILLS), modifier = Modifier.size(RecoowLayout.iconSize)) },
        value = amount?.let { { RecoowMoneyValue(it, type) } }, trailing = trailing)
}
