package imehc.recoow

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.widget.QuickToolWidget
import imehc.recoow.widget.QuickToolWidgetConfiguration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DataSettingsWidgetTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun dataPageRecreatesAndReturnsToSettings() {
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("data_settings").performClick()
        compose.onNodeWithTag("backup_export").assertIsDisplayed()
        compose.onNodeWithTag("tab_home").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("backup_import").assertIsDisplayed()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }

    @Test fun widgetInstancesConfigureIndependentlyRestoreAndDelete() {
        val host = AppWidgetHost(context, 73110)
        val manager = AppWidgetManager.getInstance(context)
        val ids = IntArray(3) { host.allocateAppWidgetId() }
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try { ids.forEach { id -> assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, QuickToolWidget::class.java))) } }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            assertNull(QuickToolWidget.selected(context, ids[0]))
            fun choose(id: Int, tool: ToolRoute) {
                ActivityScenario.launch<QuickToolWidgetConfiguration>(Intent(context, QuickToolWidgetConfiguration::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)).use {
                    compose.onNodeWithTag("widget_tool_${tool.id}").performScrollTo().performClick()
                    compose.waitForIdle()
                }
                assertEquals(tool, QuickToolWidget.selected(context, id))
            }
            choose(ids[0], ToolRoute.BILLS)
            choose(ids[1], ToolRoute.DIARY)
            choose(ids[0], ToolRoute.REMINDERS)
            assertEquals(ToolRoute.DIARY, QuickToolWidget.selected(context, ids[1]))
            val receiver = QuickToolWidget()
            receiver.onRestored(context, intArrayOf(ids[0]), intArrayOf(ids[2]))
            QuickToolWidget.renderWithPreferences(context, intArrayOf(ids[2]), imehc.recoow.core.domain.preferences.AppPreferences())
            compose.waitUntil(5_000) { QuickToolWidget.selected(context, ids[2]) == ToolRoute.REMINDERS }
            assertNull(QuickToolWidget.selected(context, ids[0]))
            assertEquals(ToolRoute.DIARY, QuickToolWidget.selected(context, ids[1]))
            receiver.onDeleted(context, intArrayOf(ids[2]))
            assertNull(QuickToolWidget.selected(context, ids[2]))
        } finally {
            ids.forEach { host.deleteAppWidgetId(it) }
            QuickToolWidget().onDeleted(context, ids)
            host.deleteHost()
        }
    }
}
