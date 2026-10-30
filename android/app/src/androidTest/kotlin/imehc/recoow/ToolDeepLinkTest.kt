package imehc.recoow

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.domain.navigation.ToolRoute
import org.junit.Rule
import org.junit.Test

class ToolDeepLinkTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun open(uri: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    @Test fun allToolsAcceptWarmLinksAndReturnHome() {
        for (route in ToolRoute.entries) {
            open("recoow://tool/${route.id}")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("tool_${route.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tool_${route.id}").assertIsDisplayed()
            compose.onNodeWithTag("tab_home").assertDoesNotExist()
            if (route in setOf(ToolRoute.BILLS, ToolRoute.REMINDERS, ToolRoute.DIARY, ToolRoute.FOOD, ToolRoute.ITEMS, ToolRoute.ANNIVERSARIES, ToolRoute.DECISION, ToolRoute.LOCATION)) {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitForIdle()
            } else compose.onNodeWithTag("return_home").performClick()
            compose.onNodeWithTag("tab_home").assertIsSelected()
        }
    }

    @Test fun newToolReplacesOldToolAndRecreationDoesNotReplayTheIntent() {
        open("recoow://tool/bills")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tool_bills").fetchSemanticsNodes().isNotEmpty() }
        open("recoow://tool/anniversaries")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tool_anniversaries").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tool_anniversaries").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("tab_home").assertIsSelected()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab_home").assertIsSelected()
    }

    @Test fun invalidLinkLeavesTheCurrentDestinationIntact() {
        compose.onNodeWithTag("tab_settings").performClick()
        open("recoow://tool/not-a-tool")
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        open("https://tool/bills")
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }
    @Test fun repeatedReminderRecordLinksReopenDetailsAndKeepBackStack() {
        repeat(2) {
            open("recoow://tool/reminders/missing-test-record")
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("reminder_detail").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reminder_detail").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("tool_reminders").fetchSemanticsNodes().isNotEmpty() }
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("tab_home").assertIsSelected()
    }

    @Test fun journalRecordLinksReopenDetailsAndReturnThroughTheirLists() {
        for ((tool, tag) in listOf("diary" to "diary_detail", "foodJournal" to "food_detail", "itemLocator" to "item_detail", "anniversaries" to "anniversary_detail", "decisionMaker" to "decision_detail", "locationTracker" to "track_detail")) {
            repeat(2) {
                open("recoow://tool/$tool/missing-test-record")
                compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                compose.activityRule.scenario.recreate()
                compose.onNodeWithTag(tag).assertIsDisplayed()
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("tool_$tool").fetchSemanticsNodes().isNotEmpty() }
                compose.activityRule.scenario.recreate()
                compose.onNodeWithTag("tool_$tool").assertIsDisplayed()
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("tab_home").assertIsSelected()
        }
    }

}
