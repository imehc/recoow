package imehc.recoow.core.media

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
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
        // 读屏器通过名称与 SetProgress 调整这些值，不使用双指缩放或旋转手势。
        compose.onNodeWithContentDescription(context.getString(R.string.crop_preview))
            .performCustomAccessibilityActionWithLabel(context.getString(R.string.crop_move_left))
        compose.onNodeWithContentDescription(context.getString(R.string.zoom)).performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithContentDescription(context.getString(R.string.rotation)).performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(90f) }
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertNull(selected); assertArrayEquals(bytes, first.readBytes())
        compose.onNodeWithTag("image_preview_pager").performTouchInput { swipeLeft() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithTag("image_preview_pager").performTouchInput { swipeRight() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun recorderProducesPlayableAacAndReleasesOnBackground() {
        var recorded by mutableStateOf<PreparedMedia?>(null)
        compose.setContent { RecoowTheme {
            if (recorded == null) AudioRecorderInput(inline = true) { recorded = it; files += it.file }
            else AudioPlayback(MediaLocator(draft = recorded!!.file), FilesRepository(), recorded!!.durationSeconds, compact = true)
        } }
        compose.onNodeWithContentDescription(context.getString(R.string.record)).performClick()
        compose.waitUntil(8000) { compose.onAllNodesWithText(context.getString(R.string.recording, "00:01")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.stop)).performClick()
        compose.waitUntil(5000) { recorded != null }
        assertTrue(recorded!!.file.length() > 0); assertEquals("audio/mp4", recorded!!.mimeType)
        compose.onNodeWithContentDescription(context.getString(R.string.play)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(context.getString(R.string.pause)).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithContentDescription(context.getString(R.string.play)).assertIsDisplayed()
        compose.runOnIdle { recorded = null }
        compose.onNodeWithContentDescription(context.getString(R.string.record)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(context.getString(R.string.stop)).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(context.getString(R.string.recording_interrupted)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.record)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription(context.getString(R.string.stop)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.cancel)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.record)).assertIsDisplayed()
    }

    @Test fun photoDragReordersAcrossRowsAndKeepsAudioAndPreview() {
        val originals = (0..3).map { index -> MediaAttachment(id = "photo-$index", kind = AttachmentKind.PHOTO,
            mimeType = "image/jpeg", content = PreparedMedia(photo(if (index % 2 == 0) Color.RED else Color.BLUE), "image/jpeg", 800, 800)) }
        val audio = MediaAttachment(id = "audio", kind = AttachmentKind.AUDIO, mimeType = "audio/mp4")
        var attachments by mutableStateOf(listOf(originals[0], audio) + originals.drop(1))
        var preview: String? = null
        compose.setContent { RecoowTheme {
            Box(Modifier.fillMaxWidth().testTag("photo_grid")) {
                val photos = attachments.filter { it.kind == AttachmentKind.PHOTO }
                AttachmentPhotoGrid(photos, FilesRepository(), onPreview = { preview = it.id },
                    onRemove = { selected -> attachments = attachments.filterNot { it.id == selected.id } },
                    onMove = { source, target -> attachments = attachments.movePhoto(source,
                        photos.indexOfFirst { it.id == target } - photos.indexOfFirst { it.id == source }) })
            }
        } }
        fun center(id: String) = compose.onNodeWithTag("attachment_photo_$id", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center - compose.onNodeWithTag("photo_grid").fetchSemanticsNode().boundsInRoot.topLeft
        val start = center("photo-0")
        val target = center("photo-3")
        val grid = compose.onNodeWithTag("photo_grid")
        grid.performTouchInput { down(start); advanceEventTime(800); moveBy(Offset(0f, 1f)) }
        compose.waitForIdle()
        grid.performTouchInput { moveTo(target, delayMillis = 300) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("photo-3", "audio", "photo-1", "photo-2", "photo-0"), attachments.map { it.id })
            assertSame(audio, attachments[1])
        }
        // 布局移动后仍需保持按住状态，同一指针不能把照片换回去。
        grid.performTouchInput { moveBy(Offset(0f, 2f), delayMillis = 200) }
        compose.waitForIdle()
        grid.performTouchInput { up() }
        compose.runOnIdle { assertEquals("photo-0", attachments.last().id) }
        compose.onNodeWithTag("attachment_photo_photo-0", useUnmergedTree = true).performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("photo-0", preview) }
        compose.onNodeWithTag("attachment_photo_remove_photo-0").performClick()
        compose.runOnIdle {
            assertFalse(attachments.any { it.id == "photo-0" })
            assertSame(audio, attachments[1])
            originals.forEach { assertTrue(it.content!!.file.exists()) }
        }
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
