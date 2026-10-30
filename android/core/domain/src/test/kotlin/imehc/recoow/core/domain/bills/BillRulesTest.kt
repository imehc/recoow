package imehc.recoow.core.domain.bills

import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class BillRulesTest {
    private val now = Instant.parse("2026-10-30T08:00:00Z").toEpochMilli()
    private fun bill(type: BillType = BillType.EXPENSE, category: String = "dining", account: String? = null) = BillRecord(
        "bill", "version", "Lunch", BillAmounts(10_000, 1_000, 9_000), type, category, PaymentMethod.WECHAT, now, accountId = account)

    @Test fun decimalGoldenCasesRoundAmountsButTruncateDiscounts() {
        listOf("12.345" to 1235L, "0.005" to 1L, "¥1,000.01" to 100001L, ".5" to 50L,
            "92233720368547758.07" to Long.MAX_VALUE).forEach { (input, expected) -> assertEquals(expected, BillMoney.cents(input)) }
        listOf("", "-1", "NaN", "Infinity", "12xyz", "1.2.3", "92233720368547758.08").forEach { assertNull(it, BillMoney.cents(it)) }
        for (rate in listOf("8.8", "88", "88%", "8.8折")) assertEquals(879L, BillMoney.final(999, BillMoney.rate(rate)!!))
        assertEquals(0L, BillMoney.final(1, BillMoney.rate("9.9")!!))
        assertNull(BillMoney.rate("101")); assertNull(BillMoney.rate("-1"))
    }
    @Test fun changingOriginalPreservesDisplayedRateAndAllCompanionsStayConsistent() {
        var state = AmountInput().edit(AmountField.ORIGINAL, "100", BillType.EXPENSE)
        state = state.edit(AmountField.FINAL, "90", BillType.EXPENSE)
        assertEquals(BillAmounts(10000, 1000, 9000), state.values(BillType.EXPENSE))
        state = state.edit(AmountField.ORIGINAL, "200", BillType.EXPENSE)
        assertEquals(BillAmounts(20000, 2000, 18000), state.values(BillType.EXPENSE))
        state = state.edit(AmountField.DISCOUNT, "50", BillType.EXPENSE)
        assertEquals("7.5", state.rate)
        state = state.edit(AmountField.RATE, "88", BillType.EXPENSE)
        assertEquals(BillAmounts(20000, 2400, 17600), state.values(BillType.EXPENSE))
        assertNull(state.edit(AmountField.FINAL, "201", BillType.EXPENSE).values(BillType.EXPENSE))
        assertNull(state.edit(AmountField.DISCOUNT, "201", BillType.EXPENSE).values(BillType.EXPENSE))
        assertNull(state.edit(AmountField.RATE, "wrong", BillType.EXPENSE).values(BillType.EXPENSE))
        assertEquals(0L, state.edit(AmountField.FINAL, "0", BillType.EXPENSE).values(BillType.EXPENSE)!!.final)
        assertNull(state.edit(AmountField.FINAL, "0", BillType.INCOME).values(BillType.INCOME))
    }
    @Test fun expiryIsDerivedAtTheExactBoundaryAndDoesNotChangeRawAmounts() {
        val active = bill(category = "groupBuy", account = "card").copy(validUntil = now)
        assertFalse(active.expired(now)); assertTrue(active.expired(now + 1))
        assertEquals(9000L, active.countedAmount(now)); assertEquals(0L, active.countedAmount(now + 1))
        assertEquals(0L, active.countedDiscount(now + 1)); assertEquals(9000L, active.amounts.final)
        assertFalse(active.canRedeem(now + 1)); assertFalse(active.canRefund(now + 1))
        assertEquals(Settlement.ACTIVE, active.settlement)
        val redeemed = active.copy(settlement = Settlement.REDEEMED)
        assertEquals(9000L, redeemed.countedAmount(now + 1)); assertFalse(redeemed.canRefund(now))
    }
    @Test fun rechargeConsumptionAndIncomeUseDifferentBalanceRules() {
        assertEquals(10000L, bill(account = "card").balanceDelta(now))
        assertEquals(-2000L, bill(BillType.STORED_VALUE_USE, account = "card").copy(amounts = BillAmounts(2000, 0, 2000)).balanceDelta(now))
        assertEquals(0L, bill(BillType.INCOME, account = "card").balanceDelta(now))
        assertEquals(0L, bill(account = "card").copy(settlement = Settlement.REFUNDED).balanceDelta(now))
    }
    @Test fun summariesUseLocalDatesVoidedAmountsAndExactLargeTotals() {
        val edge = Instant.parse("2026-10-29T16:01:00Z").toEpochMilli()
        val bills = listOf(bill().copy(occurredAt = edge), bill().copy(settlement = Settlement.REFUNDED),
            bill(BillType.INCOME).copy(amounts = BillAmounts(500, 0, 500)),
            bill(account = "card"), bill(BillType.STORED_VALUE_USE).copy(amounts = BillAmounts(1000, 0, 1000)))
        val summary = BillSummary.calculate(bills, now, ZoneId.of("Asia/Shanghai"))
        assertEquals(BigInteger.valueOf(18000), summary.todayExpense)
        assertEquals(BigInteger.valueOf(9000), summary.todayRecharge)
        assertEquals(BigInteger.valueOf(500), summary.todayIncome)
        assertEquals(BigInteger.valueOf(1000), summary.monthUse)
        val large = BillSummary.calculate(List(2) { bill().copy(amounts = BillAmounts(Long.MAX_VALUE, 0, Long.MAX_VALUE)) }, now, ZoneId.of("UTC"))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), large.todayExpense)
    }
    @Test fun typeFilterClearsIncompatibleCategoriesAndCopyRemainsOnlyADraft() {
        val filter = BillFilter(category = "dining").withType(BillType.INCOME)
        assertNull(filter.category)
        assertNull(filter.copy(incomeCategory = "salary").withType(null).incomeCategory)
        assertTrue(BillFilter(query = "  METRO ").matches(bill(), "Lunch Metro card line 2"))
        val copy = BillDraft.from(bill().copy(settlement = Settlement.REFUNDED), duplicate = true, now = now + 1)
        assertNull(copy.revision); assertNotEquals("bill", copy.id); assertEquals(now + 1, copy.occurredAt)
        assertFalse(copy.copy(title = "  ").valid)
    }
}
