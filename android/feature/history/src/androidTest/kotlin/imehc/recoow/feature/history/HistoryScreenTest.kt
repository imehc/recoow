package imehc.recoow.feature.history

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.designsystem.RecoowTheme
import imehc.recoow.core.domain.history.*
import imehc.recoow.core.domain.navigation.ToolRoute
import java.io.File
import java.time.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 只操作功能测试应用，历史数据使用内存仓库。 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(UnconfinedTestDispatcher())
    private val store = ViewModelStore()
    @After fun cleanup() { store.clear() }
    @Test fun sharedFiltersKeepDraftsAndRowsPreserveTheirNavigation() {
        val today = LocalDate.now()
        val at = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val entries = listOf(HistoryEntry("diary:1", ToolRoute.DIARY, "1", "雨后的散步", at, "沿着河岸走了一会儿，心情很平静。"),
            HistoryEntry("items:2", ToolRoute.ITEMS, "2", "备用充电器", at - 1, "玄关抽屉第二层"),
            HistoryEntry("food:3", ToolRoute.FOOD, "day:$today", "今天的饮食", at - 2, day = today, count = 3))
        val repository = object : HistoryRepository {
            override val changes = emptyFlow<Unit>()
            override suspend fun page(query: HistoryQuery, cursor: HistoryCursor?, limit: Int) = HistoryPage(entries.filter {
                (query.route == null || it.route == query.route) && (query.search.isBlank() || it.title.contains(query.search))
            }, null)
            override suspend fun counts(weekStart: LocalDate, zone: ZoneId) = mapOf(today to entries.size)
            override suspend fun hasEntries() = true
            override suspend fun prepareDeletion(entries: List<HistoryEntry>, zone: ZoneId) =
                HistoryDeletionPlan(1, zone, entries.map { HistoryDeletion(it, "test") })
            override suspend fun delete(plan: HistoryDeletionPlan) = error("No deletion during layout tests")
        }
        val vm = HistoryViewModel(repository, SavedStateHandle()).also { store.put("history", it) }
        var destination: Pair<ToolRoute, String>? = null
        compose.setContent { RecoowTheme { HistoryScreen(vm, {}) { route, id -> destination = route to id } } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        capture("history-list.png")
        compose.waitUntil(5_000) { runCatching { compose.onAllNodesWithTag("history_diary:1").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithTag("history_diary:1").performClick()
        assertEquals(ToolRoute.DIARY to "1", destination)
        capture("history-list.png")
        compose.onNodeWithTag("history_filters").performClick()
        compose.onNodeWithTag("history_filter_diary").performClick()
        assertNull(vm.route.value)
        capture("history-filters.png")
        compose.onNodeWithTag("history_apply_filters").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.route.value == ToolRoute.DIARY && !vm.state.value.loading }
        assertEquals(listOf("1"), vm.state.value.entries.map { it.recordId })
        compose.runOnIdle { vm.selecting() }
        compose.onNodeWithTag("history_cancel_selection").assertIsDisplayed()
        compose.onNodeWithTag("history_delete_selection").assertIsNotEnabled()
        compose.onNodeWithTag("history_diary:1").performClick()
        compose.onNodeWithTag("history_delete_selection").assertIsEnabled()
        capture("history-selection.png")
        compose.onNodeWithTag("history_delete_selection").performClick()
        compose.waitUntil(5_000) { vm.state.value.plan != null && !vm.state.value.busy }
        assertEquals(listOf("1"), vm.state.value.plan!!.targets.map { it.entry.recordId })
        capture("history-delete-confirmation.png")
        compose.onNodeWithText(compose.activity.getString(imehc.recoow.core.designsystem.R.string.record_cancel)).performClick()
        compose.onNodeWithTag("history_cancel_selection").performClick()
        compose.runOnIdle {
            assertFalse(vm.state.value.selecting)
            assertTrue(vm.state.value.selected.isEmpty())
            assertNull(vm.state.value.plan)
        }
        compose.onNodeWithTag("history_delete_selection").assertDoesNotExist()
        compose.onNodeWithTag("history_search_toggle").performClick()
        compose.onNodeWithTag("history_search_toggle_field").performTextInput("散步")
        compose.waitUntil(5_000) { vm.search.value == "散步" }
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
