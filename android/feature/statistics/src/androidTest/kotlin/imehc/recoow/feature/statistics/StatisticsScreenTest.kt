package imehc.recoow.feature.statistics

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.designsystem.RecoowTheme
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.statistics.*
import java.io.File
import java.time.*
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 在隔离的功能测试应用中运行，不读取也不修改用户记录。 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(UnconfinedTestDispatcher())
    @Test fun rendersRecordTrendFinancialTrendAndCategoryCharts() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        fun at(offset: Long) = today.minusDays(offset).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val rows = listOf(
            BillRecord("lunch", "1", "午餐", BillAmounts(5_200, 0, 5_200), BillType.EXPENSE, "dining", PaymentMethod.ALIPAY, at(1)),
            BillRecord("bus", "1", "地铁出行", BillAmounts(600, 0, 600), BillType.EXPENSE, "transport", PaymentMethod.WECHAT, at(2)),
            BillRecord("book", "1", "买书", BillAmounts(9_900, 0, 9_900), BillType.EXPENSE, "learning", PaymentMethod.ALIPAY, at(4)),
            BillRecord("shopping", "1", "买衣服", BillAmounts(4_000, 0, 4_000), BillType.EXPENSE, "shopping", PaymentMethod.ALIPAY, at(5)),
            BillRecord("income", "1", "兼职收入", BillAmounts(80_000, 0, 80_000), BillType.INCOME, "partTime", PaymentMethod.BANK_CARD, at(3)),
        )
        val bills = BillStatistics(rows, BillPeriod.MONTH, now, zone, Locale.getDefault())
        val state = StatisticsState(overview = usageOverview(mapOf(ToolRoute.BILLS to rows.map { it.occurredAt },
            ToolRoute.DIARY to listOf(at(0), at(1), at(3), at(6))), now, zone),
            progress = listOf(CheckInProgress("goal", "每日阅读", 12, 30, 3, 8, false),
                CheckInProgress("walk", "每日散步并坚持记录沿途见闻", 8, 20, 2, 5, false)),
            bills = bills, trend = bills.trend(), expenseShares = bills.shares(BillType.EXPENSE),
            incomeShares = bills.shares(BillType.INCOME), totals = BillMetric.entries.associateWith(bills::total))
        compose.setContent { RecoowTheme { StatisticsScreen(state) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        capture("statistics-overview.png")
        compose.waitUntil(5_000) { runCatching { compose.onAllNodesWithTag("statistics_usage_chart").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithTag("statistics_usage_chart").assertIsDisplayed()
        capture("statistics-overview.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("statistics_tools_distribution"))
        val billsTool = compose.onNodeWithTag("statistics_tool_bills").fetchSemanticsNode().boundsInRoot
        val diaryTool = compose.onNodeWithTag("statistics_tool_diary").fetchSemanticsNode().boundsInRoot
        assertEquals(billsTool.top, diaryTool.top, 1f)
        assertTrue(billsTool.right < diaryTool.right)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("statistics_progress_goal"))
        val firstGoal = compose.onNodeWithTag("statistics_progress_goal").fetchSemanticsNode().boundsInRoot
        val secondGoal = compose.onNodeWithTag("statistics_progress_walk").fetchSemanticsNode().boundsInRoot
        assertEquals(firstGoal.top, secondGoal.top, 1f)
        assertTrue(firstGoal.right < secondGoal.right)
        val titleBounds = compose.onNodeWithTag("statistics_progress_title_goal", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val progressBounds = compose.onNodeWithTag("statistics_progress_value_goal", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals(8 * density, progressBounds.top - titleBounds.bottom, 1f)
        capture("statistics-grids.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("statistics_bill_chart"))
        compose.onNodeWithTag("statistics_bill_chart").assertIsDisplayed()
        compose.onNodeWithTag("statistics_bill_chart").performTouchInput { click(center) }
        capture("statistics-trend.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("statistics_expense_donut"))
        compose.onNodeWithTag("statistics_expense_donut").assertIsDisplayed()
        capture("statistics-expense.png")
        val expenseRing = compose.onNodeWithTag("statistics_expense_donut").fetchSemanticsNode().boundsInRoot
        val incomeRing = compose.onNodeWithTag("statistics_income_donut").fetchSemanticsNode().boundsInRoot
        assertEquals(expenseRing.top, incomeRing.top, 1f)
        compose.onNodeWithTag("statistics_expense_category_transport").assertDoesNotExist()
        compose.onNodeWithTag("statistics_expense_expand").performScrollTo().performClick()
        compose.onNodeWithTag("statistics_expense_category_transport").assertExists()
        compose.onNodeWithTag("statistics_expense_expand").performScrollTo().performClick()
        compose.onNodeWithTag("statistics_expense_category_transport").assertDoesNotExist()

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("statistics_metric_0"))
        val expenseBounds = compose.onNodeWithTag("statistics_metric_0").fetchSemanticsNode().boundsInRoot
        val incomeBounds = compose.onNodeWithTag("statistics_metric_1").fetchSemanticsNode().boundsInRoot
        val lowerBounds = (2..4).map { compose.onNodeWithTag("statistics_metric_$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(expenseBounds.top, incomeBounds.top, 1f)
        lowerBounds.forEach { assertEquals(lowerBounds.first().top, it.top, 1f) }
        assertTrue(lowerBounds.first().top >= expenseBounds.bottom)
        lowerBounds.forEach { assertTrue(it.width > 0) }
        capture("statistics-metrics.png")
        for (index in BillMetric.entries.indices) {
            compose.onNodeWithTag("statistics_metric_$index").performScrollTo()
            compose.onNodeWithTag("statistics_metric_$index").performClick()
            compose.onNodeWithTag("statistics_details_header").assertIsDisplayed()
            val labels = listOf(R.string.statistics_expense, R.string.statistics_income, R.string.statistics_bill_count,
                R.string.statistics_discount, R.string.statistics_stored_use)
            val title = compose.onNode(hasText(compose.activity.getString(labels[index])) and hasAnyAncestor(hasTestTag("statistics_details_header")))
                .fetchSemanticsNode().boundsInRoot
            val values = compose.onNodeWithTag("statistics_details_header").onChildren().fetchSemanticsNodes()
            assertEquals(2, values.size)
            val amount = values.last().boundsInRoot
            assertEquals(title.center.y, amount.center.y, 1f)
            assertTrue(title.right <= amount.left)
            compose.onNodeWithContentDescription(compose.activity.getString(imehc.recoow.core.designsystem.R.string.record_cancel)).assertDoesNotExist()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            compose.onNodeWithTag("statistics_bill_details").assertDoesNotExist()
        }
        compose.onNodeWithTag("statistics_metric_0").performScrollTo()
        compose.onNodeWithTag("statistics_metric_0").performClick()
        compose.onNodeWithTag("statistics_bill_details").assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("午餐").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("午餐").assertExists()
        capture("statistics-details.png")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
