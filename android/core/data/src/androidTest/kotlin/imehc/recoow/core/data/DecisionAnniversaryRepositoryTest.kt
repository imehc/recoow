package imehc.recoow.core.data

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import imehc.recoow.core.backup.*
import imehc.recoow.core.data.anniversaries.RoomAnniversariesRepository
import imehc.recoow.core.data.decision.RoomDecisionsRepository
import imehc.recoow.core.data.media.RoomMediaRepository
import imehc.recoow.core.database.DatabaseHost
import imehc.recoow.core.domain.anniversaries.*
import imehc.recoow.core.domain.calendar.DateCalendar
import imehc.recoow.core.domain.decision.*
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.StaleRecordException
import java.io.File
import java.math.BigInteger
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class DecisionAnniversaryRepositoryTest {
    private lateinit var root: File; private lateinit var host: DatabaseHost
    private lateinit var decisions: RoomDecisionsRepository; private lateinit var anniversaries: RoomAnniversariesRepository; private lateinit var media: RoomMediaRepository
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "decision-anniversary-${UUID.randomUUID()}").apply { mkdirs() }; val live = File(root, "live")
        host = DatabaseHost(context, live, LocalRollbackFiles(live, File(root, "rollback"), object : BackupPreferences {
            override suspend fun capture() = "{}"; override suspend fun restore(json: String) = Unit
        }, "test"))
        decisions = RoomDecisionsRepository(host, "test", ticket = { BigInteger.ZERO }); anniversaries = RoomAnniversariesRepository(host, "test")
        media = RoomMediaRepository(host, File(root, "media-cache"), "test")
    }
    @After fun cleanup() = runBlocking { host.close(); root.deleteRecursively(); Unit }
    private fun photo(): PreparedMedia {
        val file = File(root, "photo.jpg"); val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }; bitmap.recycle(); return PreparedMedia(file, "image/jpeg", 32, 24)
    }
    private suspend fun rejects(block: suspend () -> Unit) { try { block(); fail("Expected rejection") } catch (_: StaleRecordException) { } }
    @Test fun choiceSnapshotsAndImagesSurviveSourceEditsAndCollectionDeletionWithoutHistoryLimit() = runBlocking {
        decisions.saveCollection(CollectionDraft(id = "collection", title = "Lunch"))
        decisions.saveOption(OptionDraft(id = "option", collectionId = "collection", title = "Burger", detail = "Original", customInfo = "Sauce", imageChanged = true, imageContent = photo(), hasImage = true))
        decisions.saveOption(OptionDraft(id = "disabled", collectionId = "collection", title = "Disabled", enabled = false))
        repeat(13) { decisions.choose("collection") }
        val before = decisions.snapshot.first(); assertEquals(13, before.history.size); assertTrue(before.history.all { it.title == "Burger" && it.detail == "Original" && it.customInfo == "Sauce" })
        assertEquals(MediaAvailability.READY, media.resolve(before.history.first().image).availability)
        val original = before.options.single { it.id == "option" }
        decisions.saveOption(OptionDraft.from(original).copy(title = "Changed", imageChanged = true, imageContent = null, imageAssetId = null, hasImage = false))
        rejects { decisions.saveOption(OptionDraft.from(original)) }
        val collection = decisions.snapshot.first().collections.single(); decisions.deleteCollection(collection.id, collection.revision)
        val after = decisions.snapshot.first(); assertTrue(after.collections.isEmpty()); assertTrue(after.options.isEmpty()); assertEquals(before.history, after.history)
        assertEquals(MediaAvailability.READY, media.resolve(after.history.first().image).availability)
        val deleted = after.history.first(); decisions.deleteChoice(deleted.id, deleted.revision); assertEquals(12, decisions.snapshot.first().history.size)
    }
    @Test fun invalidOptionRollsBackAndTextEditPreservesAsset() = runBlocking {
        decisions.saveCollection(CollectionDraft(id = "collection", title = "Test"))
        val asset = media.createImage(photo())
        decisions.saveOption(OptionDraft(id = "option", collectionId = "collection", title = "Asset", imageChanged = true, imageAssetId = asset, hasImage = true))
        val current = decisions.snapshot.first().options.single()
        decisions.saveOption(OptionDraft.from(current).copy(detail = "Text"))
        assertEquals(asset, decisions.snapshot.first().options.single().imageAssetId)
        try { decisions.saveOption(OptionDraft(id = "invalid", collectionId = "collection", title = "Bad", weight = "101")); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, decisions.snapshot.first().options.size)
        val c = decisions.snapshot.first().collections.single(); decisions.deleteCollection(c.id, c.revision)
        try { decisions.saveOption(OptionDraft(collectionId = c.id, title = "Orphan")); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(decisions.snapshot.first().options.isEmpty())
    }
    @Test fun allAnniversaryCategoriesAndCalendarsRoundTripWithStaleEditProtection() = runBlocking {
        for ((index, category) in AnniversaryCategory.entries.withIndex()) anniversaries.save(AnniversaryDraft(id = category.wire, title = category.wire,
            category = category, calendar = if (index % 2 == 0) DateCalendar.CHINESE else DateCalendar.GREGORIAN, lead = 1440, minutes = 615))
        val records = anniversaries.records.first(); assertEquals(6, records.size); assertTrue(records.all { it.minutes == 615 && it.lead == 1440 })
        val first = records.first(); anniversaries.save(AnniversaryDraft.from(first).copy(enabled = false, yearly = false))
        rejects { anniversaries.save(AnniversaryDraft.from(first)) }
        val updated = anniversaries.records.first().single { it.id == first.id }; assertFalse(updated.enabled); assertFalse(updated.yearly)
        anniversaries.delete(updated.id, updated.revision); assertEquals(5, anniversaries.records.first().size)
    }
}
