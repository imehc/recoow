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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
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

    private fun openSettings() {
        compose.onNodeWithTag("tab_settings").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("appearance").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun choose(tag: String, index: Int) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("preference_option_$index").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun syncScreenRestoresAddressAndReturnsToSettings() {
        openSettings()
        compose.onNodeWithTag("sync_settings").performScrollTo().performClick()
        compose.onNodeWithTag("sync_endpoint").performTextInput("https://example.com/recoow")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("sync_endpoint").assertTextContains("https://example.com/recoow")
        compose.onNodeWithTag("tab_settings").assertDoesNotExist()
        compose.onNodeWithTag("sync_back").performClick()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }

    @Test fun allTabsAndRecreationRetainSelectedDestination() {
        compose.onNodeWithTag("tab_home").assertIsSelected()
        compose.onNodeWithTag("tab_statistics").performClick().assertIsSelected()
        openSettings()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.onNodeWithTag("appearance").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.onNodeWithTag("language").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tab_home").performClick().assertIsSelected()
    }

    @Test fun appearanceSelectionPersistsAndBackDismissesDialog() {
        openSettings()
        choose("appearance", 2)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("appearance").performClick()
        compose.onNodeWithTag("preference_option_2").assertIsSelected()
        pressBack()
        compose.onNodeWithTag("preference_option_2").assertDoesNotExist()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }

    @Test fun chineseAndEnglishSurviveLocaleRecreation() {
        openSettings()
        choose("language", 1)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("外观").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        compose.onNodeWithText("外观").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("外观").assertIsDisplayed()
        choose("language", 2)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Appearance").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }

    @Test fun canceledSelectionDoesNotChangeStoredAppearance() {
        openSettings()
        choose("appearance", 1)
        compose.onNodeWithTag("appearance").performClick()
        compose.onNodeWithTag("preference_option_1").assertIsSelected()
        pressBack()
        compose.onNodeWithTag("appearance").performClick()
        compose.onNodeWithTag("preference_option_1").assertIsSelected()
        pressBack()
    }

    @Test fun systemOptionsCanBeRestoredAndBackReturnsToHome() {
        openSettings()
        choose("appearance", 0)
        choose("language", 0)
        compose.onNodeWithTag("appearance").performClick()
        compose.onNodeWithTag("preference_option_0").assertIsSelected()
        pressBack()
        compose.onNodeWithTag("language").performClick()
        compose.onNodeWithTag("preference_option_0").assertIsSelected()
        pressBack()
        pressBack()
        compose.onNodeWithTag("tab_home").assertIsSelected()
    }

    @Test fun dialogRestoresAcrossActivityRecreation() {
        openSettings()
        compose.onNodeWithTag("language").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("preference_option_2").performScrollTo().assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun systemLanguageChangesUpdateSettingsWithoutBeingOverwritten() {
        openSettings()
        choose("language", 2)
        compose.activityRule.scenario.onActivity {
            it.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("zh-Hans")
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("外观").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("language").performClick()
        compose.onNodeWithTag("preference_option_1").assertIsSelected()
        pressBack()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("外观").assertIsDisplayed()
        compose.onNodeWithTag("language").performClick()
        compose.onNodeWithTag("preference_option_1").assertIsSelected()
        pressBack()
    }
}
