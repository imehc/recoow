package imehc.recoow

import android.app.LocaleManager
import android.os.LocaleList
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun pressBack() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun settingsRow(tag: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        // Lazy rows outside a small viewport do not have semantics nodes yet.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun openSettings() {
        compose.onNodeWithTag("tab_settings").performClick()
        val preferences = androidx.lifecycle.ViewModelProvider(compose.activity)[imehc.recoow.feature.settings.SettingsViewModel::class.java]
        compose.waitUntil(10_000) { preferences.uiState.value.preferences != null }
        settingsRow("appearance").assertIsDisplayed()
    }

    private fun choose(tag: String, index: Int) {
        settingsRow(tag).performClick()
        compose.onNodeWithTag("preference_option_$index").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun syncScreenRestoresAddressAndReturnsThroughAccountSettings() {
        openSettings()
        settingsRow("account_security").performClick()
        val enableCloud = compose.activity.getString(imehc.recoow.feature.settings.R.string.account_enable_cloud)
        compose.onNodeWithText(enableCloud).performClick()
        compose.onNodeWithTag("sync_endpoint").performTextInput("https://example.com/recoow")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("sync_endpoint").assertTextContains("https://example.com/recoow")
        compose.onNodeWithTag("tab_settings").assertDoesNotExist()
        compose.onNodeWithTag("sync_back").performClick()
        compose.onNodeWithText(enableCloud).assertIsDisplayed()
        compose.onNodeWithTag("tab_settings").assertDoesNotExist()
        pressBack()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }

    @Test fun allTabsAndRecreationRetainSelectedDestination() {
        compose.onNodeWithTag("tab_home").assertIsSelected()
        compose.onNodeWithTag("tab_statistics").performClick().assertIsSelected()
        openSettings()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        settingsRow("appearance").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        settingsRow("language").assertIsDisplayed()
        compose.onNodeWithTag("tab_home").performClick().assertIsSelected()
    }

    @Test fun appearanceSelectionPersistsAndBackDismissesDialog() {
        openSettings()
        choose("appearance", 2)
        compose.activityRule.scenario.recreate()
        settingsRow("appearance").performClick()
        compose.onNodeWithTag("preference_option_2").assertIsSelected()
        pressBack()
        compose.onNodeWithTag("preference_option_2").assertDoesNotExist()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }

    @Test fun chineseAndEnglishSurviveLocaleRecreation() {
        openSettings()
        choose("language", 1)
        settingsRow("appearance")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("外观").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.onNodeWithText("外观").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("外观").assertIsDisplayed()
        choose("language", 2)
        settingsRow("appearance")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Appearance").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }

    @Test fun canceledSelectionDoesNotChangeStoredAppearance() {
        openSettings()
        choose("appearance", 1)
        settingsRow("appearance").performClick()
        compose.onNodeWithTag("preference_option_1").assertIsSelected()
        pressBack()
        settingsRow("appearance").performClick()
        compose.onNodeWithTag("preference_option_1").assertIsSelected()
        pressBack()
    }

    @Test fun systemOptionsCanBeRestoredAndBackReturnsToHome() {
        openSettings()
        choose("appearance", 0)
        choose("language", 0)
        settingsRow("appearance").performClick()
        compose.onNodeWithTag("preference_option_0").assertIsSelected()
        pressBack()
        settingsRow("language").performClick()
        compose.onNodeWithTag("preference_option_0").assertIsSelected()
        pressBack()
        pressBack()
        compose.onNodeWithTag("tab_home").assertIsSelected()
    }

    @Test fun dialogRestoresAcrossActivityRecreation() {
        openSettings()
        settingsRow("language").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("preference_option_2").performScrollTo().assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }

    @Test fun photoPreferenceIsOneNamedSwitchAndPersistsAfterRecreation() {
        openSettings()
        val label = compose.activity.getString(imehc.recoow.feature.settings.R.string.photos_picked)
        val selector = hasText(label) and isToggleable()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(selector)
        compose.onNode(selector).performScrollTo()
        compose.onAllNodes(selector).assertCountEquals(1)
        val preferences = androidx.lifecycle.ViewModelProvider(compose.activity)[imehc.recoow.feature.settings.SettingsViewModel::class.java]
        val original = preferences.uiState.value.preferences!!.addsPickedPhotosToMediaLibrary
        try {
            compose.onNode(selector).performClick()
            compose.waitUntil(10_000) { !preferences.uiState.value.saving && preferences.uiState.value.preferences!!.addsPickedPhotosToMediaLibrary != original }
            compose.activityRule.scenario.recreate()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(selector)
            compose.onNode(selector).performScrollTo()
            if (original) compose.onNode(selector).assertIsOff() else compose.onNode(selector).assertIsOn()
        } finally {
            compose.runOnIdle { preferences.setPickedPhotos(original) }
            compose.waitUntil(10_000) { !preferences.uiState.value.saving }
        }
    }

    @Test fun reminderSwitchAndScheduleAreNamedAndRestoreWithoutSaving() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("recoow://tool/reminders"), activity, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reminder_add").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        compose.onNodeWithTag("reminder_add").performClick()
        val label = compose.activity.getString(imehc.recoow.feature.reminders.R.string.reminder_enabled)
        val selector = hasText(label) and isToggleable()
        compose.onNode(selector).performScrollTo().assertIsOn().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNode(selector).performScrollTo().assertIsOff()
        val schedule = compose.activity.getString(imehc.recoow.feature.reminders.R.string.reminder_schedule)
        compose.onNodeWithContentDescription(schedule).performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                compose.activity.getString(imehc.recoow.feature.reminders.R.string.reminder_goal)))
            .performClick()
        pressBack()
        pressBack()
        compose.onNodeWithTag("reminder_add").assertIsDisplayed()
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun systemLanguageChangesUpdateSettingsWithoutBeingOverwritten() {
        openSettings()
        choose("language", 2)
        compose.activityRule.scenario.onActivity {
            it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-Hans")
        }
        settingsRow("appearance")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("外观").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        settingsRow("language").performClick()
        compose.onNodeWithTag("preference_option_1").assertIsSelected()
        pressBack()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("外观").assertIsDisplayed()
        settingsRow("language").performClick()
        compose.onNodeWithTag("preference_option_1").assertIsSelected()
        pressBack()
    }
}
