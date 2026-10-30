package imehc.recoow.core.domain.bills

import java.io.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

enum class AmountField { ORIGINAL, FINAL, DISCOUNT, RATE }

/** Decimal throughout: ordinary amounts round half-up to cents; discount multiplication truncates. */
object BillMoney {
    fun cents(text: String): Long? = parse(text.replace(",", "").replace("¥", ""))?.let {
        runCatching { it.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }.getOrNull()
    }
    fun input(cents: Long) = BigDecimal.valueOf(cents, 2).toPlainString()
    fun rate(text: String): BigDecimal? = parse(text.replace(",", "").replace("折", "").replace("%", ""))?.let {
        when { it <= BigDecimal.TEN -> it.movePointLeft(1); it <= BigDecimal(100) -> it.movePointLeft(2); else -> null }
    }
    fun final(original: Long, rate: BigDecimal): Long = BigDecimal.valueOf(original).multiply(rate).setScale(0, RoundingMode.DOWN).longValueExact()
    fun rateInput(original: Long, final: Long): String = if (original <= 0) "" else
        BigDecimal.valueOf(final).multiply(BigDecimal.TEN).divide(BigDecimal.valueOf(original), 2, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    private fun parse(raw: String): BigDecimal? {
        val text = raw.trim()
        if (text.length !in 1..80 || !text.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) return null
        return text.toBigDecimalOrNull()
    }
}

data class AmountInput(val original: String = "", val final: String = "", val discount: String = "",
    val rate: String = "", val last: AmountField = AmountField.ORIGINAL,
    val companionField: AmountField = AmountField.DISCOUNT) : Serializable {
    fun values(type: BillType): BillAmounts? {
        if (type != BillType.EXPENSE) return BillMoney.cents(final)?.takeIf { it > 0 }?.let { BillAmounts(it, 0, it) }
        val originalCents = BillMoney.cents(original)?.takeIf { it > 0 } ?: return null
        val finalCents = when (last) {
            AmountField.RATE -> BillMoney.rate(rate)?.let { BillMoney.final(originalCents, it) }
            AmountField.DISCOUNT -> (if (discount.isBlank()) 0L else BillMoney.cents(discount))?.takeIf { it <= originalCents }?.let { originalCents - it }
            else -> BillMoney.cents(final)
        } ?: return null
        return BillAmounts(originalCents, originalCents - finalCents, finalCents).takeIf { it.valid(type) }
    }
    fun edit(field: AmountField, text: String, type: BillType): AmountInput {
        val next = when (field) {
            AmountField.ORIGINAL -> copy(original = text, last = field)
            AmountField.FINAL -> copy(final = text, last = field)
            AmountField.DISCOUNT -> copy(discount = text, last = field)
            AmountField.RATE -> copy(rate = text, last = field)
        }.copy(companionField = if (field == AmountField.ORIGINAL) companionField else field)
        if (type != BillType.EXPENSE) return next
        val originalCents = BillMoney.cents(next.original)?.takeIf { it > 0 }
        if (originalCents == null) return next.copy(final = if (field == AmountField.FINAL) text else "",
            discount = if (field == AmountField.DISCOUNT) text else "", rate = if (field == AmountField.RATE) text else "")
        val finalCents = when (field) {
            AmountField.ORIGINAL -> BillMoney.rate(rate)?.let { BillMoney.final(originalCents, it) }
                ?: if (companionField == AmountField.FINAL) BillMoney.cents(final)?.takeIf { it <= originalCents }
                else (if (discount.isBlank()) 0L else BillMoney.cents(discount))?.takeIf { it <= originalCents }?.let { originalCents - it }
            AmountField.FINAL -> BillMoney.cents(text)?.takeIf { it <= originalCents }
            AmountField.DISCOUNT -> (if (text.isBlank()) 0L else BillMoney.cents(text))?.takeIf { it <= originalCents }?.let { originalCents - it }
            AmountField.RATE -> BillMoney.rate(text)?.let { BillMoney.final(originalCents, it) }
        }
        return next.copy(final = if (field == AmountField.FINAL) text else finalCents?.let(BillMoney::input).orEmpty(),
            discount = if (field == AmountField.DISCOUNT) text else finalCents?.let { BillMoney.input(originalCents - it) }.orEmpty(),
            rate = if (field == AmountField.RATE) text else finalCents?.let { BillMoney.rateInput(originalCents, it) }.orEmpty())
    }
    fun withType(type: BillType) = if (type == BillType.EXPENSE) {
        val next = copy(original = original.ifBlank { final }); next.edit(AmountField.ORIGINAL, next.original, type)
    } else copy(final = final.ifBlank { original }, discount = "", rate = "", last = AmountField.FINAL)
    companion object {
        fun from(values: BillAmounts) = AmountInput(BillMoney.input(values.original), BillMoney.input(values.final),
            BillMoney.input(values.discount), BillMoney.rateInput(values.original, values.final), AmountField.FINAL, AmountField.FINAL)
    }
}
