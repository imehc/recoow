package imehc.recoow

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.diary.*
import imehc.recoow.core.domain.food.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.feature.bills.BillsViewModel
import imehc.recoow.feature.diary.DiaryViewModel
import imehc.recoow.feature.food.FoodViewModel
import imehc.recoow.feature.history.HistoryViewModel
import imehc.recoow.feature.settings.SettingsViewModel
import imehc.recoow.feature.statistics.StatisticsViewModel
import java.time.*
import java.util.UUID
import org.junit.*
import org.junit.Assert.*

class OverviewNavigationTest {
    // 来源导航包含轨迹页；当前位置预览的系统授权由定位专项验证。
    @get:Rule(order = 0) val location = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val prefix = "overview-${UUID.randomUUID()}"
    private lateinit var bills: BillsViewModel; private lateinit var food: FoodViewModel; private lateinit var diary: DiaryViewModel
    private lateinit var settings: SettingsViewModel; private var hidden = emptySet<String>()
    @Before fun setup() {
        compose.runOnIdle {
            val provider = ViewModelProvider(compose.activity)
            bills = provider[BillsViewModel::class.java]; food = provider[FoodViewModel::class.java]
            diary = provider[DiaryViewModel::class.java]; settings = provider[SettingsViewModel::class.java]
        }
        compose.waitUntil(10_000) { !bills.state.value.loading && !food.state.value.loading && !diary.state.value.loading && settings.uiState.value.preferences != null }
        hidden = settings.uiState.value.preferences!!.hiddenHomeTools
    }
    @After fun cleanup() {
        if (!::bills.isInitialized) return
        for (entry in bills.state.value.bills.filter { it.id.startsWith(prefix) }) {
            compose.runOnIdle { bills.delete(entry) }; compose.waitUntil(10_000) { !bills.state.value.busy }
        }
        for (entry in food.state.value.data.entries.filter { it.id.startsWith(prefix) }) {
            compose.runOnIdle { food.delete(entry) }; compose.waitUntil(10_000) { !food.state.value.busy }
        }
        for (entry in diary.state.value.data.entries.filter { it.id.startsWith(prefix) }) {
            compose.runOnIdle { diary.delete(entry) }; compose.waitUntil(10_000) { !diary.state.value.busy }
        }
        for (route in ToolRoute.entries) {
            compose.runOnIdle { settings.setToolVisible(route.id, route.id !in hidden) }
            compose.waitUntil(10_000) { !settings.uiState.value.saving }
        }
    }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    // 深链和语言重建期间根视图会短暂不存在，等待目标页面而非提前抛错。
    private fun waitTag(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() } }
    private fun clickRow(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun open(tool: String, id: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("recoow://tool/$tool/$id"), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    @Test fun hiddenToolsKeepTotalsAndHistoryRoutesToBillAndFoodDayAcrossRecreation() {
        val now = System.currentTimeMillis(); val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        compose.runOnIdle { bills.update(BillDraft(id = "$prefix-bill", title = "Overview bill", occurredAt = now, amounts = AmountInput(original = "12.00", final = "10.00"))); bills.save() }
        compose.waitUntil(10_000) { bills.state.value.bills.any { it.id == "$prefix-bill" } }
        compose.runOnIdle { bills.closeDetail(); food.update(FoodDraft(id = "$prefix-food", title = "Overview lunch", occurredAt = now)); food.save() }
        compose.waitUntil(10_000) { food.state.value.data.entries.any { it.id == "$prefix-food" } }
        compose.runOnIdle { food.back(); food.back() }
        compose.onNodeWithTag("tab_statistics").performClick()
        lateinit var stats: StatisticsViewModel
        compose.runOnIdle { stats = ViewModelProvider(compose.activity)[StatisticsViewModel::class.java] }
        compose.waitUntil(10_000) { stats.state.value.loading.isEmpty() && stats.state.value.overview.total >= 2 }
        val total = stats.state.value.overview.total
        for (route in ToolRoute.entries) {
            compose.runOnIdle { settings.setToolVisible(route.id, false) }
            compose.waitUntil(10_000) { !settings.uiState.value.saving && route.id in settings.uiState.value.preferences!!.hiddenHomeTools }
        }
        compose.onNodeWithTag("tab_home").performClick(); compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("home_bills").assertDoesNotExist()
        compose.onNodeWithTag("tab_settings").assertIsDisplayed()
        compose.onNodeWithTag("tab_statistics").performClick()
        compose.waitUntil(10_000) { stats.state.value.overview.total == total }
        compose.onNodeWithTag("statistics_history").performClick(); waitTag("history_page")
        lateinit var history: HistoryViewModel
        compose.runOnIdle { history = ViewModelProvider(compose.activity)[HistoryViewModel::class.java]; history.route(ToolRoute.BILLS) }
        compose.waitUntil(10_000) { history.state.value.entries.any { it.recordId == "$prefix-bill" } && !history.state.value.loading }
        clickRow("history_bills:$prefix-bill")
        compose.onNodeWithText("Overview bill").assertIsDisplayed()
        compose.activityRule.scenario.recreate(); compose.onNodeWithText("Overview bill").assertIsDisplayed()
        back(); waitTag("history_page")
        compose.runOnIdle { history.route(ToolRoute.FOOD) }
        compose.waitUntil(10_000) { history.state.value.entries.any { it.day == day } && !history.state.value.loading }
        clickRow("history_foodJournal:$day"); waitTag("food_day_detail")
        compose.onNodeWithText("Overview lunch").assertIsDisplayed()
        compose.activityRule.scenario.recreate(); waitTag("food_day_detail")
        back(); waitTag("history_page"); back()
        compose.onNodeWithTag("tab_statistics").assertIsSelected()
    }
    @Test fun diarySixSourceRoutesReturnToTheSameDiaryAndShowMissingRecords() {
        val links = DiarySource.entries.map { DiaryLink(it.wire, "$prefix-missing", "Source ${it.wire}", icon = it.icon) }
        compose.runOnIdle { diary.update(DiaryDraft(id = "$prefix-diary", title = "Overview links", content = "Six source routes", links = links)); diary.save("Diary") }
        compose.waitUntil(10_000) { diary.state.value.data.entries.any { it.id == "$prefix-diary" } }
        open("diary", "$prefix-diary"); waitTag("diary_detail")
        val tags = mapOf(DiarySource.TRACK to "track_detail", DiarySource.BILL to "bill_detail", DiarySource.REMINDER to "reminder_detail",
            DiarySource.ANNIVERSARY to "anniversary_detail", DiarySource.ITEM to "item_detail", DiarySource.CHOICE to "decision_detail")
        for (source in DiarySource.entries) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Source ${source.wire}"))
            compose.onNodeWithText("Source ${source.wire}").performClick()
            if (source == DiarySource.BILL) {
                compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(imehc.recoow.feature.bills.R.string.missing_bill)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            } else waitTag(tags.getValue(source))
            back(); waitTag("diary_detail")
            compose.onNodeWithText("Overview links").assertIsDisplayed()
        }
        compose.activityRule.scenario.recreate(); waitTag("diary_detail")
    }
}
