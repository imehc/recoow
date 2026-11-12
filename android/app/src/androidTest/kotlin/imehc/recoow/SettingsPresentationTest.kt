package imehc.recoow

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.designsystem.RecoowTheme
import imehc.recoow.core.domain.backup.StorageUsage
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.domain.preferences.Appearance
import imehc.recoow.core.domain.sync.*
import imehc.recoow.core.media.ImageProcessor
import imehc.recoow.feature.media.*
import imehc.recoow.feature.settings.*
import imehc.recoow.feature.settings.R as SettingsR
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 注入的状态与回调不会访问用户记录、服务器或偏好。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(UnconfinedTestDispatcher())
    private val usage = StorageUsage(9_000_000, 42_000_000, 12_000_000, 3_000_000, 1_000_000, null, null, 4)
    private val sync = SyncState(loaded = true, connection = SyncConnection("https://example.com/recoow", "demo-account", "android", true),
        enabled = true, pending = 12, pendingMedia = 3, lastSuccessAt = 1_794_288_000_000,
        devices = listOf(SyncDevice("android", "Android", true, ""), SyncDevice("iphone", "iPhone", false, "2026-11-10 14:32")))

    @Test fun webEditingWaitsForServerAndRestrictsControlToPrimaryDevice() {
        var account by mutableStateOf(AccountSecurityState(loaded = true, supported = true, username = "合成示例", webEditing = false))
        var connection by mutableStateOf(sync)
        var requested: Boolean? = null
        compose.setContent { RecoowTheme { AccountSecurityScreen(account, connection, {}, {}, {}, {}, {}, { _, _ -> },
            { _, _, _ -> }, {}, {}, {}, {}, onWebEditing = { requested = it; account = account.copy(busy = true) }) } }
        row("account_web_access").assertIsOff().performClick()
        compose.runOnIdle { assertEquals(true, requested) }
        row("account_web_access").assertIsOff().assertIsNotEnabled()
        capture("web-access-saving.png")
        compose.runOnIdle { account = account.copy(busy = false, webEditing = true, result = AccountResult.WEB_ACCESS_UPDATED) }
        row("account_web_access").assertIsOn().assertIsEnabled()
        capture("web-access-enabled.png")
        compose.runOnIdle { account = account.copy(problem = AccountProblem.CONNECTION, result = null) }
        row("account_web_access").assertIsOn()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(compose.activity.getString(SettingsR.string.account_error_connection)))
        capture("web-access-error.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(compose.activity.getString(SettingsR.string.retry)))
        compose.onNodeWithText(compose.activity.getString(SettingsR.string.retry)).assertIsDisplayed()
        capture("web-access-error-bottom.png")
        compose.runOnIdle { connection = connection.copy(connection = requireNotNull(connection.connection).copy(canPair = false)) }
        compose.onNodeWithTag("account_web_access").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(SettingsR.string.account_web_access_section)).assertDoesNotExist()
        row("account_connection").assertExists()
        capture("web-access-secondary.png")
    }

    @Test fun settingsSubpagesRestoreAndReturnToTheirEntry() {
        val restore = StateRestorationTester(compose)
        var prefs by mutableStateOf(AppPreferences())
        val media = fakeMedia()
        val processor = ImageProcessor(compose.activity)
        restore.setContent { RecoowTheme {
            RecoowApp(SettingsUiState(preferences = prefs), { prefs = prefs.copy(appearance = it) }, {}, {},
                onPickedPhotos = { prefs = prefs.copy(addsPickedPhotosToMediaLibrary = it) },
                dataScreen = { back, assets, photos -> DataManagementContent(DataManagementState(usage), back, assets, photos,
                    {}, {}, {}, {}, { _, _, _ -> }, {}, {}) },
                mediaScreen = { back -> MediaLibraryContent(MediaLibraryState(preferences = prefs, loading = false), media, processor,
                    back, {}, {}, { _, _ -> }, {}, { _, _ -> }, { _, _ -> }, {}) })
        } }
        compose.onNodeWithTag("tab_home").assertIsSelected()
        capture("settings-tab-home.png")
        compose.onNodeWithTag("tab_statistics").performClick().assertIsSelected()
        capture("settings-tab-statistics.png")
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("appearance").assertDoesNotExist()
        compose.onNodeWithTag("photos_picked").assertDoesNotExist()
        capture("settings-root.png")
        row("display_settings").performClick()
        compose.onNodeWithTag("tab_settings").assertDoesNotExist()
        row("appearance").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("record_option_1").performClick()
        compose.runOnIdle { assertEquals(Appearance.LIGHT, prefs.appearance) }
        capture("settings-display.png")
        compose.onNodeWithTag("settings_back").performClick()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
        row("home_tools_settings").performClick()
        compose.onNodeWithTag("setting_tool_bills").assertExists()
        back()
        row("data_settings").performClick()
        row("backup_export").assertIsDisplayed()
        capture("settings-data.png")
        row("media_settings").performClick()
        compose.onNodeWithTag("photos_picked").assertDoesNotExist()
        compose.onNodeWithTag("media_add").assertIsDisplayed()
        compose.onNodeWithTag("media_back").performClick()
        row("photo_settings").performClick()
        val old = prefs.addsPickedPhotosToMediaLibrary
        row("photos_picked").performClick()
        compose.runOnIdle { assertEquals(!old, prefs.addsPickedPhotosToMediaLibrary) }
        restore.emulateSavedInstanceStateRestore()
        capture("settings-photos.png")
        back()
        row("storage_settings").performClick()
        capture("settings-storage.png")
        restore.emulateSavedInstanceStateRestore()
        row("data_reset").assertExists()
        back()
        row("backup_export").assertIsDisplayed()
        back()
        compose.onNodeWithTag("tab_settings").assertIsSelected()
    }

    @Test fun accountPanelsKeepDevicePermissionsAndPasswordValidation() {
        val restore = StateRestorationTester(compose)
        var syncCalls = 0
        var revoked: String? = null
        var changed: Pair<String, String>? = null
        var pairing = 0
        restore.setContent { RecoowTheme { AccountSecurityScreen(AccountSecurityState(loaded = true, supported = true, username = "review-account"),
            sync, {}, {}, {}, {}, {}, { old, next -> changed = old to next }, { _, _, _ -> }, { syncCalls++ }, {}, {}, {}, {},
            { revoked = it }, { _, _ -> }, { pairing++ }) } }
        compose.onNodeWithTag("sync_now").assertIsDisplayed()
        capture("settings-account.png")
        row("sync_now").performClick()
        compose.runOnIdle { assertEquals(1, syncCalls) }
        row("sync_details").performClick()
        capture("settings-sync-details.png")
        restore.emulateSavedInstanceStateRestore()
        back()
        row("account_devices").performClick()
        compose.onNodeWithText("Android").assertIsDisplayed()
        compose.onAllNodesWithTag("top_more").assertCountEquals(1)
        capture("settings-devices.png")
        compose.onNodeWithTag("top_more").performClick()
        compose.onNodeWithText(compose.activity.getString(SettingsR.string.sync_revoke)).performClick()
        compose.runOnIdle { assertNull(revoked) }
        back()
        compose.onNodeWithTag("sync_pair_device").performClick()
        compose.runOnIdle { assertEquals(1, pairing); assertNull(revoked) }
        back()
        row("account_conflicts").performClick()
        compose.onNodeWithText(compose.activity.getString(SettingsR.string.settings_no_conflicts)).assertExists()
        back()
        row("account_password_security").performClick()
        row("account_change_password").performClick()
        capture("settings-password.png")
        compose.onNodeWithTag("account_current_password").performTextInput("old-pass1")
        compose.onNodeWithTag("account_new_password").performTextInput("new-pass1")
        compose.onNodeWithTag("account_confirm_password").performTextInput("different")
        row("account_password_save").performClick()
        compose.runOnIdle { assertNull(changed) }
        compose.onNodeWithText(compose.activity.getString(SettingsR.string.account_password_mismatch)).assertExists()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("account_current_password").assertTextEquals("")
        compose.onNodeWithTag("account_new_password").assertTextEquals("")
        back()
        row("account_change_password").assertExists()
        back()
        row("account_devices").assertExists()
    }

    @Test fun mediaSelectionGuardsReferencesAndDragCommitsAtRelease() {
        val restore = StateRestorationTester(compose)
        var assets by mutableStateOf((1..12).map { i -> MediaAsset("asset-$i", listOf("沿途随拍", "周末早餐", "收纳标签")[(i-1)%3],
            "image/png", 12000, "demo", 400, 400, i.toLong(), if (i == 2) 3 else 0) })
        var deleted: Set<String>? = null
        var reorder: List<String>? = null
        val media = fakeMedia()
        val processor = ImageProcessor(compose.activity)
        restore.setContent { RecoowTheme { MediaLibraryContent(MediaLibraryState(assets, AppPreferences(), loading = false), media, processor,
            {}, {}, {}, { _, _ -> }, { deleted = it }, { _, _ -> }, { _, _ -> }, { ids ->
                reorder = ids; assets = ids.map { id -> assets.first { it.id == id } }
            }) } }
        capture("settings-media-grid.png")
        val first = compose.onNodeWithTag("asset_asset-1").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("asset_asset-3").fetchSemanticsNode().boundsInRoot
        val bounds = compose.onNodeWithTag("media_grid").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, third.top, 1f)
        compose.onNodeWithTag("media_grid").performTouchInput {
            down(Offset(first.center.x - bounds.left, first.top + first.width / 2 - bounds.top))
            advanceEventTime(650)
            moveTo(Offset(third.center.x - bounds.left, third.top + third.width / 2 - bounds.top), delayMillis = 180)
        }
        compose.runOnIdle { assertNull(reorder) }
        compose.onNodeWithTag("media_grid").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertNotNull(reorder); assertEquals("asset-1", reorder!![2]) }
        compose.onNodeWithTag("media_more").performClick()
        compose.onNodeWithTag("media_select").performClick()
        compose.onNodeWithTag("asset_select_asset-2").assertIsNotEnabled()
        compose.onNodeWithTag("asset_select_asset-1").performClick().assertIsOn()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("asset_select_asset-1").assertIsOn()
        capture("settings-media-selection.png")
        compose.onNodeWithTag("media_delete_selected").performClick()
        compose.runOnIdle { assertNull(deleted) }
        capture("settings-media-delete.png")
        compose.onNodeWithTag("media_confirm_delete").performClick()
        compose.runOnIdle { assertEquals(setOf("asset-1"), deleted) }
        compose.onNodeWithTag("media_view").performClick()
        capture("settings-media-list.png")
        compose.onNodeWithTag("asset_more_asset-2").performClick()
        compose.onNodeWithTag("asset_delete_asset-2").assertIsNotEnabled()
        back()
        compose.onNodeWithTag("media_view").performClick()
        compose.onNodeWithTag("media_grid").performTouchInput {
            down(0, Offset(center.x - 75, center.y)); down(1, Offset(center.x + 75, center.y))
            moveTo(0, Offset(center.x - 25, center.y), delayMillis = 100)
            moveTo(1, Offset(center.x + 25, center.y), delayMillis = 100)
            up(0); up(1)
        }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("asset_asset-3").fetchSemanticsNode().boundsInRoot.width < first.width)
        capture("settings-media-dense.png")
    }

    @Test fun connectionHasNoDuplicateSyncAndDoesNotRestoreCredentials() {
        val restore = StateRestorationTester(compose)
        var connection by mutableStateOf(sync)
        var connects = 0
        restore.setContent { RecoowTheme { SyncScreen(connection, {}, { _, _, _, _ -> connects++ }, {}, {}, {}, { _, _ -> }, {}, {}) } }
        compose.onNodeWithTag("sync_now").assertDoesNotExist()
        compose.onNodeWithTag("sync_enabled").assertDoesNotExist()
        capture("settings-connection.png")
        compose.runOnIdle { connection = SyncState(loaded = true) }
        compose.onNodeWithTag("sync_endpoint").performTextInput("https://example.com/recoow")
        compose.onNodeWithTag("sync_secret").performTextInput("demo-pair-code")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("sync_endpoint").assertTextContains("https://example.com/recoow")
        compose.onNodeWithTag("sync_secret").assertTextEquals("")
        compose.runOnIdle { assertEquals(0, connects) }
        capture("settings-connect-form.png")
    }

    private fun row(tag: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(150) // Wait for the emulator compositor, beyond Compose's virtual clock.
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun fakeMedia(): MediaRepository {
        val image = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "settings-fixture.png")
        Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(204, 220, 233))
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(135, 165, 167) }
            android.graphics.Canvas(this).drawCircle(200f, 200f, 110f, paint)
            image.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }; recycle()
        }
        return Proxy.newProxyInstance(MediaRepository::class.java.classLoader, arrayOf(MediaRepository::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "IsolatedMediaRepository"
                "getAssets" -> flowOf(emptyList<MediaAsset>())
                "observeContent" -> flowOf(ResolvedMedia(MediaAvailability.READY, image, cacheKey = image.path))
                "resolve" -> ResolvedMedia(MediaAvailability.READY, image)
                else -> error("Unexpected user repository access: ${method.name}")
            }
        } as MediaRepository
    }
}
