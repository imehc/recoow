package imehc.recoow

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import imehc.recoow.feature.reminders.RemindersViewModel
import imehc.recoow.feature.reminders.R as ReminderR
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReminderNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val title = "Reminder navigation ${UUID.randomUUID()}"
    private val viewModel get() = ViewModelProvider(compose.activity)[RemindersViewModel::class.java]

    private fun ready() {
        compose.waitUntil(10_000) { !viewModel.state.value.loading && !viewModel.state.value.busy }
        compose.waitForIdle()
    }

    private fun open(id: String) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("recoow://tool/reminders/$id"), activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reminder_detail")
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    @After fun cleanup() {
        ready()
        viewModel.state.value.records.filter { it.title == title }.forEach { record ->
            compose.runOnIdle { viewModel.delete(record) }
            ready()
            compose.waitUntil(10_000) { viewModel.state.value.records.none { it.id == record.id } }
        }
    }

    @Test fun recordLinkIsConsumedAcrossEditorAndListRecreation() {
        ready()
        compose.runOnIdle {
            viewModel.edit()
            viewModel.update(requireNotNull(viewModel.draft.value).copy(title = title, enabled = false))
            viewModel.save()
        }
        ready()
        compose.waitUntil(10_000) { viewModel.state.value.records.any { it.title == title } }
        val record = viewModel.state.value.records.single { it.title == title }
        open(record.id)
        compose.onNodeWithText(compose.activity.getString(ReminderR.string.reminder_edit)).performClick()
        compose.onNodeWithTag("reminder_title").performTextReplacement("Unsaved draft")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("reminder_title").assertTextContains("Unsaved draft")

        compose.onNodeWithContentDescription(compose.activity.getString(ReminderR.string.reminder_back)).performClick()
        compose.onNodeWithTag("reminder_detail").assertIsDisplayed()
        assertEquals(title, viewModel.state.value.records.single { it.id == record.id }.title)
        compose.onNodeWithContentDescription(compose.activity.getString(ReminderR.string.reminder_back)).performClick()
        compose.onNodeWithTag("tool_reminders").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tool_reminders").assertIsDisplayed()

        // A fresh delivery of the same external link must still reopen the detail.
        open(record.id)
        compose.onNodeWithTag("reminder_detail").assertIsDisplayed()
    }
}
