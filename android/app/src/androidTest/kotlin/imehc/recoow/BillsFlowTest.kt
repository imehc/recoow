package imehc.recoow

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import imehc.recoow.feature.bills.BillsViewModel
import imehc.recoow.feature.bills.R as BillsR
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BillsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val title = "Bills UI ${UUID.randomUUID()}"
    private val viewModel get() = ViewModelProvider(compose.activity)[BillsViewModel::class.java]
    private fun label(id: Int) = compose.activity.getString(id)
    private fun ready() { compose.waitUntil(10_000) { !viewModel.state.value.loading && !viewModel.state.value.busy }; compose.waitForIdle() }
    private fun open() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("recoow://tool/bills"), activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("bill_add").fetchSemanticsNodes().isNotEmpty() }
        ready()
    }
    private fun back() {
        compose.onNodeWithContentDescription(label(BillsR.string.back)).performClick()
        compose.waitForIdle()
    }
    private fun scroll(tag: String) {
        if (!tag.startsWith("account_")) compose.onNodeWithTag(if (tag == "bill_search") "bills_list" else "bill_form").performScrollToNode(hasTestTag(tag))
    }
    private fun text(tag: String, value: String) { scroll(tag); compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value) }
    private fun clickText(id: Int) { compose.onNodeWithText(label(id)).performScrollTo().performClick(); compose.waitForIdle() }
    private fun choose(tag: String, id: Int) {
        compose.onNodeWithTag("bill_form").performScrollToNode(hasTestTag(tag))
        compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction()).performClick()
        compose.onNodeWithText(label(id)).performClick()
    }
    @After fun cleanup() {
        ready()
        for (bill in viewModel.state.value.bills.filter { it.title == title }) {
            compose.runOnIdle { viewModel.delete(bill) }; ready()
            compose.waitUntil(10_000) { viewModel.state.value.bills.none { it.id == bill.id } }
        }
        compose.waitUntil(10_000) { viewModel.state.value.accounts.filter { it.name == title }.all { it.references == 0 } }
        for (account in viewModel.state.value.accounts.filter { it.name == title }) {
            compose.runOnIdle { viewModel.deleteAccount(account) }; ready()
            compose.waitUntil(10_000) { viewModel.state.value.accounts.none { it.id == account.id } }
        }
    }

    @Test fun linkedAmountsDraftRestoreDuplicateCancellationAndSearch() {
        open(); compose.onNodeWithTag("bill_add").performClick()
        text("bill_title", title); text("amount_original", "100"); text("amount_rate", "88")
        scroll("amount_final"); compose.onNodeWithTag("amount_final").assertTextContains("88.00")
        text("amount_original", "200")
        compose.onNodeWithTag("amount_final").assertTextContains("176.00")
        compose.activityRule.scenario.recreate()
        scroll("amount_final"); compose.onNodeWithTag("amount_final").assertTextContains("176.00")
        scroll("bill_title"); compose.onNodeWithTag("bill_title").assertTextContains(title)
        compose.onNodeWithTag("bill_save").performClick(); ready()
        compose.waitUntil(10_000) { viewModel.draft.value == null && viewModel.state.value.bills.any { it.title == title } }
        assertEquals(17_600L, viewModel.state.value.bills.single { it.title == title }.amounts.final)
        clickText(BillsR.string.copy)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("bill_title").assertTextContains(title)
        back()
        assertEquals(1, viewModel.state.value.bills.count { it.title == title })
        back(); text("bill_search", "no matching ${UUID.randomUUID()}")
        compose.onNodeWithText(label(BillsR.string.no_matches)).performScrollTo().assertIsDisplayed()
        text("bill_search", title)
        val row = "bill_${viewModel.state.value.bills.single { it.title == title }.id}"
        compose.onNodeWithTag("bills_list").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        compose.onNodeWithText(label(BillsR.string.bill_details)).assertIsDisplayed()
    }

    @Test fun inlineAccountRechargeAndStoredValueUseUpdateBalanceAndReferences() {
        open(); compose.onNodeWithTag("bill_add").performClick()
        text("bill_title", title); text("amount_original", "100"); text("amount_final", "90")
        compose.onNodeWithTag("bill_form").performScrollToNode(hasTestTag("bill_recharge"))
        compose.onNodeWithTag("bill_recharge").performClick()
        compose.onNodeWithTag("bill_add_account").performScrollTo().performClick()
        text("account_name", title); text("account_balance", "50")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("account_balance").assertTextContains("50")
        compose.onNodeWithTag("account_save").performClick(); ready()
        compose.waitUntil(10_000) { viewModel.accountDraft.value == null && viewModel.draft.value?.accountId != null }
        val id = viewModel.draft.value!!.accountId!!
        compose.onNodeWithTag("bill_save").performClick(); ready()
        compose.waitUntil(10_000) { viewModel.state.value.accounts.any { it.id == id && it.balance == 15_000L } }
        back(); compose.onNodeWithTag("bill_add").performClick(); text("bill_title", title)
        choose("bill_type", BillsR.string.stored_value_use); text("amount_final", "20")
        // The account chooser is below the date. Select the account created by the first bill.
        compose.onNodeWithTag("bill_form").performScrollToNode(hasText(label(BillsR.string.select_account)))
        compose.onNode(hasText(label(BillsR.string.select_account)) and hasClickAction()).performClick()
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithTag("bill_save").performClick(); ready()
        compose.waitUntil(10_000) { viewModel.state.value.accounts.any { it.id == id && it.balance == 13_000L && it.references == 2 } }
        back(); compose.onNodeWithTag("bill_accounts").performClick()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(label(BillsR.string.delete)).assertIsNotEnabled()
    }
}
