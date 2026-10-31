package imehc.recoow.core.media

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.designsystem.RecoowTheme
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.RecordChange
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MediaComponentsTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()
    private val files = mutableListOf<File>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @After fun cleanup() { files.forEach { it.delete() } }
    private fun photo(color: Int) = File(context.cacheDir, "ui-${UUID.randomUUID()}.jpg").also { file ->
        files += file
        val bitmap = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }; bitmap.recycle()
    }
    @OptIn(ExperimentalTestApi::class)
    @Test fun cancelCropPreservesOriginalAndPreviewPageSurvivesRecreation() {
        val first = photo(Color.RED); val second = photo(Color.BLUE)
        val bytes = first.readBytes()
        var editor by mutableStateOf(true)
        var selected: PreparedMedia? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { RecoowTheme {
            if (editor) PhotoCropEditor(PreparedMedia(first, "image/jpeg", 800, 800), ImageProcessor(context),
                onCancel = { editor = false }, onConfirm = { selected = it })
            else ImagePreview(listOf(MediaLocator(draft = first), MediaLocator(draft = second)), 0, FilesRepository()) {}
        } }
        // Screen readers adjust these by name and SetProgress, without pinch/rotate gestures.
        compose.onNodeWithContentDescription(context.getString(R.string.crop_preview))
            .performCustomAccessibilityActionWithLabel(context.getString(R.string.crop_move_left))
        compose.onNodeWithContentDescription(context.getString(R.string.zoom)).performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithContentDescription(context.getString(R.string.rotation)).performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(90f) }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertNull(selected); assertArrayEquals(bytes, first.readBytes())
        compose.onNodeWithText(context.getString(R.string.next)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.previous)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun recorderProducesPlayableAacAndReleasesOnBackground() {
        var recorded by mutableStateOf<PreparedMedia?>(null)
        compose.setContent { RecoowTheme {
            if (recorded == null) AudioRecorderInput { recorded = it; files += it.file }
            else AudioPlayback(MediaLocator(draft = recorded!!.file), FilesRepository(), recorded!!.durationSeconds)
        } }
        compose.onNodeWithText(context.getString(R.string.record)).performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithText(context.getString(R.string.recording, "00:01")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.stop)).performClick()
        compose.waitUntil(5000) { recorded != null }
        assertTrue(recorded!!.file.length() > 0); assertEquals("audio/mp4", recorded!!.mimeType)
        compose.onNodeWithText(context.getString(R.string.play)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(context.getString(R.string.pause)).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(context.getString(R.string.play)).assertIsDisplayed()
        compose.runOnIdle { recorded = null }
        compose.onNodeWithText(context.getString(R.string.record)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(context.getString(R.string.stop)).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(context.getString(R.string.recording_interrupted)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.record)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(context.getString(R.string.stop)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
    }

    private class FilesRepository : MediaRepository {
        override val assets = flowOf(emptyList<MediaAsset>())
        override fun observeContent(locator: MediaLocator) = flowOf(ResolvedMedia(MediaAvailability.READY, locator.draft))
        override suspend fun resolve(locator: MediaLocator) = ResolvedMedia(MediaAvailability.READY, locator.draft)
        override suspend fun createImage(content: PreparedMedia, title: String?) = error("Not used")
        override suspend fun rename(id: String, title: String?) = error("Not used")
        override suspend fun replaceImage(id: String, content: PreparedMedia) = error("Not used")
        override suspend fun reorder(ids: List<String>) = error("Not used")
        override suspend fun delete(id: String) = error("Not used")
        override fun attachments(owner: AttachmentOwner) = flowOf(emptyList<MediaAttachment>())
        override suspend fun saveAttachments(owner: AttachmentOwner, attachments: List<MediaAttachment>, ownerChange: RecordChange?) = error("Not used")
        override suspend fun collectOrphans() = 0
    }
}
