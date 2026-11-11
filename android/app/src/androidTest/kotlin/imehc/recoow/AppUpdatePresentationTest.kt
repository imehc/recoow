package imehc.recoow

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.designsystem.RecoowTheme
import imehc.recoow.core.domain.update.*
import imehc.recoow.feature.settings.*
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 展示层回调与文件选择器、网络下载和系统安装器相互隔离。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdatePresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(UnconfinedTestDispatcher())

    @Test fun backupActionsAndUpdateStatesKeepTheirExistingOperations() {
        val release = AppRelease("0.6.1", 7, "https://example.invalid/app.apk", "a".repeat(64), 28_400_000, 26,
            "## 本次更新\n- 调整设置分组和二级页面\n- 优化素材库图片浏览与排序\n## 修复\n- 改善返回与下载状态展示")
        var data by mutableStateOf(true)
        var state by mutableStateOf(AppUpdateUiState(InstalledAppVersion("0.6.0", 6), checked = true, release = release))
        var exports = 0; var imports = 0; var checks = 0; var downloads = 0; var cancels = 0; var installs = 0
        compose.setContent { RecoowTheme {
            if (data) DataManagementContent(DataManagementState(), {}, {}, {}, { exports++ }, { imports++ }, {}, {}, { _, _, _ -> }, {}, {})
            else AppUpdateContent(state, {}, { checks++ }, { downloads++ }, { cancels++ }, { installs++ })
        } }
        compose.onNodeWithTag("backup_export").performClick()
        compose.onNodeWithTag("backup_import").performClick()
        compose.runOnIdle { assertEquals(1, exports); assertEquals(1, imports) }
        capture("backup-compact.png")
        compose.runOnIdle { data = false }
        compose.onNodeWithTag("update_download").assertIsDisplayed().performClick()
        compose.onNodeWithTag("update_check").performClick()
        compose.runOnIdle { assertEquals(1, downloads); assertEquals(1, checks) }
        capture("update-available.png")
        compose.runOnIdle { state = state.copy(busy = true, progress = 0.42f) }
        compose.onNodeWithTag("update_check").assertIsNotEnabled()
        compose.onNodeWithTag("update_download").assertDoesNotExist()
        compose.onNodeWithTag("update_install").assertDoesNotExist()
        compose.onNodeWithTag("update_cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancels) }
        capture("update-downloading.png")
        compose.runOnIdle { state = state.copy(busy = false, progress = null, prepared = PreparedAppUpdate(release)) }
        compose.onNodeWithTag("update_install").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, installs) }
        capture("update-ready.png")
        compose.runOnIdle { state = state.copy(needsInstallPermission = true) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("update_permission"))
        compose.onNodeWithTag("update_permission").assertIsDisplayed()
        compose.runOnIdle { state = AppUpdateUiState(state.installed, checked = true) }
        compose.onNodeWithTag("update_current").assertIsDisplayed()
        compose.onNodeWithTag("update_download").assertDoesNotExist()
        capture("update-current.png")
        compose.runOnIdle { state = state.copy(problem = AppUpdateProblem.CONNECTION) }
        compose.onNodeWithTag("update_current").assertDoesNotExist()
        compose.onNodeWithTag("update_check").assertIsEnabled()
        capture("update-error.png")
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(150)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
