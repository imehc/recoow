package imehc.recoow

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.designsystem.RecoowTheme
import imehc.recoow.core.designsystem.recordBackAction
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.preferences.PreferencesRepository
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.media.ImageProcessor
import imehc.recoow.feature.reminders.RemindersScreen
import imehc.recoow.feature.reminders.RemindersViewModel
import imehc.recoow.feature.settings.SettingsUiState
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 注入隔离的导航界面与内存仓库，不使用任何用户记录。 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordSourceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(UnconfinedTestDispatcher())
    private val store = ViewModelStore()
    @After fun cleanup() { store.clear() }

    @Test fun internalRecordReturnsToSourceWhileEditorsAndExternalLinksKeepTheirLevels() {
        val notifications = object : ReminderNotifications {
            override val state = MutableStateFlow(ReminderNotificationState())
            override fun requestReconcile() = Unit
            override suspend fun refreshAccess() = Unit
        }
        val reminders = RemindersViewModel(
            readOnly<RemindersRepository>(mapOf("getReminders" to MutableStateFlow(emptyList<Reminder>()))),
            readOnly<MediaRepository>(emptyMap()), ImageProcessor(compose.activity),
            readOnly<PreferencesRepository>(mapOf("getPreferences" to MutableStateFlow(AppPreferences()))),
            notifications, SavedStateHandle(),
        ).also { store.put("reminders", it) }
        val restore = StateRestorationTester(compose)
        var pending by mutableStateOf<String?>(null)
        val recordId = "missing-/中文?record"
        restore.setContent {
            RecoowTheme {
                RecoowApp(SettingsUiState(), {}, {}, {}, pendingTool = pending, onToolConsumed = { pending = null },
                    homeScreen = { _, tool -> Button(onClick = { tool(ToolRoute.REMINDERS.id) }, modifier = Modifier.testTag("open_module")) { Text("Module") } },
                    statisticsScreen = { history, tool -> Column {
                        Button(onClick = history, modifier = Modifier.testTag("open_history")) { Text("History") }
                        ToolRoute.entries.forEach { route ->
                            Button(onClick = { tool(route, recordId) }, modifier = Modifier.testTag("open_${route.id}")) { Text(route.id) }
                        }
                    } },
                    historyScreen = { back, record -> Column {
                        Text("History", Modifier.testTag("test_history"))
                        Button(onClick = { record(ToolRoute.REMINDERS, recordId) }, modifier = Modifier.testTag("history_record")) { Text("Record") }
                        Button(onClick = back) { Text("Back") }
                    } },
                    remindersScreen = { id, back -> RemindersScreen(reminders, back, id) },
                    billsScreen = { id, back -> TestRecordScreen(ToolRoute.BILLS, id, back) },
                    journalScreen = { route, id, back, source -> TestRecordScreen(route, id, back, source) },
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("tab_statistics").performClick()
        for (route in ToolRoute.entries) {
            compose.onNodeWithTag("open_${route.id}").performClick()
            if (route == ToolRoute.REMINDERS) {
                compose.onNodeWithTag("reminder_detail").assertIsDisplayed()
                compose.runOnIdle { assertEquals(recordId, reminders.detailId.value); reminders.edit() }
                compose.waitForIdle()
                compose.runOnIdle { assertNotNull(reminders.draft.value) }
                restore.emulateSavedInstanceStateRestore()
                back()
                compose.onNodeWithTag("reminder_detail").assertIsDisplayed()
                compose.runOnIdle { assertNull(reminders.draft.value) }
            } else compose.onNodeWithTag("test_detail_${route.id}").assertIsDisplayed()
            // 工具栏返回与系统返回两条路径都要覆盖。
            if (route == ToolRoute.DIARY) compose.onNodeWithTag("test_back").performClick() else back()
            compose.onNodeWithTag("tab_statistics").assertIsSelected()
        }
        compose.onNodeWithTag("open_history").performClick()
        compose.onNodeWithTag("history_record").performClick()
        compose.onNodeWithTag("reminder_detail").assertIsDisplayed()
        back()
        compose.onNodeWithTag("test_history").assertIsDisplayed()
        back()
        compose.onNodeWithTag("tab_statistics").assertIsSelected()

        compose.onNodeWithTag("open_${ToolRoute.DIARY.id}").performClick()
        compose.onNodeWithTag("test_source").performClick()
        compose.onNodeWithTag("reminder_detail").assertIsDisplayed()
        back()
        compose.onNodeWithTag("test_detail_${ToolRoute.DIARY.id}").assertIsDisplayed()
        back()
        compose.onNodeWithTag("tab_statistics").assertIsSelected()

        compose.runOnIdle { pending = "${ToolRoute.REMINDERS.id}?recordId=external" }
        compose.onNodeWithTag("reminder_detail").assertIsDisplayed()
        back()
        compose.onNodeWithTag("tool_${ToolRoute.REMINDERS.id}").assertIsDisplayed()
        back()
        compose.onNodeWithTag("tab_home").assertIsSelected()
        compose.onNodeWithTag("open_module").performClick()
        compose.onNodeWithTag("tool_${ToolRoute.REMINDERS.id}").assertIsDisplayed()
        back()
        compose.onNodeWithTag("tab_home").assertIsSelected()
    }

    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    private inline fun <reified T> readOnly(getters: Map<String, Any>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            getters[method.name] ?: error("Unexpected repository access: ${method.name}")
        } as T
}

@Composable
private fun TestRecordScreen(route: ToolRoute, id: String?, onBack: () -> Unit,
    onSource: (ToolRoute, String) -> Unit = { _, _ -> }) {
    var detail by rememberSaveable { mutableStateOf(id != null) }
    val back = recordBackAction(false, detail, detail, { detail = false }, onBack)
    BackHandler(detail, back)
    Column {
        Text(id.orEmpty(), Modifier.testTag(if (detail) "test_detail_${route.id}" else "test_module_${route.id}"))
        Button(onClick = back, modifier = Modifier.testTag("test_back")) { Text("Back") }
        Button(onClick = { onSource(ToolRoute.REMINDERS, "linked") }, modifier = Modifier.testTag("test_source")) { Text("Source") }
    }
}
