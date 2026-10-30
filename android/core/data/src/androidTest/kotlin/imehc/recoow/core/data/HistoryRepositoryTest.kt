package imehc.recoow.core.data

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.turbine.test
import imehc.recoow.core.backup.*
import imehc.recoow.core.data.anniversaries.RoomAnniversariesRepository
import imehc.recoow.core.data.bills.RoomBillsRepository
import imehc.recoow.core.data.decision.RoomDecisionsRepository
import imehc.recoow.core.data.history.RoomHistoryRepository
import imehc.recoow.core.data.journal.*
import imehc.recoow.core.data.media.RoomMediaRepository
import imehc.recoow.core.data.records.RoomRecordRepository
import imehc.recoow.core.data.reminders.RoomRemindersRepository
import imehc.recoow.core.data.statistics.RoomStatisticsRepository
import imehc.recoow.core.data.tracks.RoomTracksRepository
import imehc.recoow.core.database.*
import imehc.recoow.core.database.generated.EntityContracts
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.diary.*
import imehc.recoow.core.domain.decision.*
import imehc.recoow.core.domain.food.*
import imehc.recoow.core.domain.history.*
import imehc.recoow.core.domain.location.GeoCoordinate
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.records.RecordChange
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.domain.statistics.usageOverview
import imehc.recoow.core.domain.tracks.*
import java.io.File
import java.time.*
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

class HistoryRepositoryTest {
    private lateinit var root: File; private lateinit var host: DatabaseHost; private lateinit var records: RoomRecordRepository
    private lateinit var history: RoomHistoryRepository; private lateinit var statistics: RoomStatisticsRepository
    private lateinit var bills: RoomBillsRepository; private lateinit var reminders: RoomRemindersRepository
    private lateinit var food: RoomFoodRepository; private lateinit var diary: RoomDiaryRepository; private lateinit var tracks: RoomTracksRepository
    private val zone = ZoneId.of("Asia/Shanghai"); private val day = LocalDate.of(2026, 10, 30)
    private val timestamp = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(); private var clock = timestamp
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "history-${UUID.randomUUID()}").apply { mkdirs() }; val live = File(root, "live")
        host = DatabaseHost(context, live, LocalRollbackFiles(live, File(root, "rollback"), object : BackupPreferences {
            override suspend fun capture() = "{}"; override suspend fun restore(json: String) = Unit
        }, "test"))
        records = RoomRecordRepository(host); val media = RoomMediaRepository(host, File(root, "media-cache"), "test")
        bills = RoomBillsRepository(host, "test") { clock }; reminders = RoomRemindersRepository(host, "test", { clock }, { zone })
        food = RoomFoodRepository(host, media, "test") { clock }; diary = RoomDiaryRepository(host, media, "test", context, { clock }, { zone })
        tracks = RoomTracksRepository(host, "test") { clock }
        history = RoomHistoryRepository(host, bills, reminders, food, diary, RoomItemsRepository(host, "test"),
            RoomDecisionsRepository(host, "test"), RoomAnniversariesRepository(host, "test"), tracks)
        statistics = RoomStatisticsRepository(host)
    }
    @After fun cleanup() = runBlocking { host.close(); root.deleteRecursively(); Unit }
    private fun seed(table: String, id: String, patch: JsonObject = buildJsonObject {}): RecordChange {
        val fields = buildJsonObject { for (f in EntityContracts.registry.entity(table).fields) {
            if (f.derived || f.binary || f.name in setOf("id", "created_at", "updated_at", "deleted_at", "device_id", "server_version")) continue
            put(f.name, when { f.nullable -> JsonNull; f.default != null -> f.default!!; f.enumValues.isNotEmpty() -> JsonPrimitive(f.enumValues.first())
                f.jsonText -> JsonPrimitive("[]"); f.type == "int" -> JsonPrimitive(timestamp); f.type == "real" -> JsonPrimitive(1.0); f.type == "bool" -> JsonPrimitive(true); else -> JsonPrimitive("Seed") })
        } }
        return RecordChange(table, id, JsonObject(fields + patch).toString(), clock, "test")
    }
    @Test fun eightSourcesSameTimestampPageWithoutGapsAndUseMealDatesForFiltersAndBadges() = runBlocking {
        val rows = (0..80).map { seed("bills", "bill-${it.toString().padStart(3, '0')}") } +
            (0..2).map { seed("diary_entries", "diary-$it", buildJsonObject { put("content", if (it == 0) "100%_literal" else "x".repeat(100_000)) }) } +
            listOf(seed("tracks", "track"), seed("decision_choice_records", "choice"), seed("stored_items", "item"), seed("anniversaries", "anniversary"),
                seed("reminders", "reminder", buildJsonObject { put("scheduled_at", timestamp - 86_400_000); put("schedule_kind", "dailyGoal") }),
                seed("reminder_completions", "reminder:2026-10-29", buildJsonObject { put("reminder_id", "reminder"); put("date_key", "2026-10-29"); put("completed_at", timestamp) }),
                seed("reminder_completions", "reminder:2026-10-30", buildJsonObject { put("reminder_id", "reminder"); put("date_key", "2026-10-30"); put("completed_at", timestamp) }),
                seed("food_entries", "meal-a"), seed("food_entries", "meal-b"))
        records.save(rows)
        clock += 86_400_000; food.saveDayTitle(day.atStartOfDay(zone).toInstant().toEpochMilli(), "Edited tomorrow", null)
        val entries = mutableListOf<HistoryEntry>(); var cursor: HistoryCursor? = null
        do { val page = history.page(HistoryQuery(zone = zone), cursor, 7); entries += page.entries; cursor = page.next; assertTrue(entries.size <= 91) } while (cursor != null)
        assertEquals(91, entries.size); assertEquals(91, entries.map { it.key }.toSet().size)
        assertEquals(ToolRoute.entries.toSet(), entries.map { it.route }.toSet())
        assertEquals(ToolRoute.FOOD, entries.first().route); assertEquals(2, entries.first().count)
        assertEquals(91, history.counts(day, zone)[day]); assertNull(history.counts(day, zone)[day.plusDays(1)])
        assertEquals(91, history.page(HistoryQuery(date = day, zone = zone), limit = 200).entries.size)
        assertTrue(history.page(HistoryQuery(date = day.plusDays(1), zone = zone), limit = 200).entries.isEmpty())
        assertEquals("diary-0", history.page(HistoryQuery(search = "%_", zone = zone)).entries.single().recordId)
        assertEquals(81, history.page(HistoryQuery(route = ToolRoute.BILLS, zone = zone), limit = 200).entries.size)
        assertTrue(entries.filter { it.route == ToolRoute.DIARY }.all { it.detail.length <= 180 })
        val overview = usageOverview(ToolRoute.entries.associateWith { statistics.dates(it).first() }, timestamp, zone)
        assertEquals(90, overview.total); assertEquals(89, overview.today); assertEquals(2, overview.activeDays)
        host.close(); assertEquals(91, history.page(HistoryQuery(zone = zone), limit = 200).entries.size)
    }
    @Test fun deletionDispatchesBusinessRulesAndKeepsLinkedSourcesAndOtherCompletions() = runBlocking {
        bills.saveAccount("account", null, "Card", AccountKind.MEMBERSHIP, 10_000, null)
        bills.save(BillDraft(id = "purchase", title = "Use", type = BillType.STORED_VALUE_USE, accountId = "account", amounts = AmountInput(final = "7.00"), occurredAt = timestamp))
        assertEquals(9300L, bills.accounts.first().single().balance)
        reminders.save(ReminderDraft(id = "reminder", title = "Goal", scheduledAt = timestamp - 86_400_000, kind = ScheduleKind.GOAL))
        reminders.makeUp("reminder", setOf(day.minusDays(1)), "Yesterday"); reminders.act("reminder", ReminderAction.CHECK_IN)
        food.save(FoodDraft(id = "meal-a", title = "Lunch", occurredAt = timestamp, billIds = listOf("purchase")))
        food.save(FoodDraft(id = "meal-b", title = "Dinner", occurredAt = timestamp + 1000))
        val session = tracks.begin("Route", 10); clock += 30_000
        tracks.checkpoint(session, listOf(TrackPoint(coordinate = GeoCoordinate(31.2, 121.4), time = timestamp)), 30, TrackStatus.FINISHED)
        diary.save(DiaryDraft(id = "linked", title = "Keep", links = listOf(DiaryLink("bill", "purchase", "Use", icon = "receipt.fill"))), "Diary")
        val all = history.page(HistoryQuery(zone = zone), limit = 200).entries
        val selected = all.filter { it.route in setOf(ToolRoute.BILLS, ToolRoute.FOOD, ToolRoute.LOCATION) || it.route == ToolRoute.REMINDERS && it.day == day }
        val plan = history.prepareDeletion(selected, zone); assertEquals(4, plan.targets.size)
        val result = history.delete(plan); assertTrue(result.failed.isEmpty()); assertEquals(4, result.deleted.size)
        assertEquals(10_000L, bills.accounts.first().single().balance)
        assertEquals(listOf(day.minusDays(1)), reminders.reminders.first().single().completions.map { it.date })
        assertTrue(food.snapshot.first().entries.isEmpty()); assertEquals("purchase", diary.snapshot.first().entries.single().links.single().sourceId)
        assertTrue(tracks.tracks.first().isEmpty())
        host.access { db -> assertTrue(db.records().trackRows(session.track.id).all { Json.parseToJsonElement(it.json).jsonObject["deleted_at"] != JsonNull }) }
    }
    @Test fun stalePlansDoNotDeleteChangedRowsAndDateStreamsRefreshOnWritesAndReopen() = runBlocking {
        records.save(listOf(seed("diary_entries", "safe"), seed("diary_entries", "changed")))
        val plan = history.prepareDeletion(history.page(HistoryQuery(zone = zone)).entries, zone)
        clock++; records.save(listOf(RecordChange("diary_entries", "changed", "{\"title\":\"New title\"}", clock, "remote")))
        val result = history.delete(plan); assertEquals(setOf("diary:safe"), result.deleted); assertEquals(setOf("diary:changed"), result.failed)
        assertEquals("New title", diary.snapshot.first().entries.single().title)
        val replacementPlan = history.prepareDeletion(history.page(HistoryQuery(zone = zone)).entries, zone)
        host.close(); assertEquals(1, history.delete(replacementPlan).failed.size)
        statistics.dates(ToolRoute.DIARY).test {
            assertEquals(listOf(timestamp), awaitItem())
            records.save(listOf(seed("diary_entries", "new", buildJsonObject { put("occurred_at", timestamp + 86_400_000) })))
            assertEquals(setOf(timestamp, timestamp + 86_400_000), awaitItem().toSet())
            host.close(); assertEquals(2, statistics.dates(ToolRoute.DIARY).first().size)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(history.hasEntries())
    }
    @Test fun deletingChoiceCollectionKeepsItsHistoryAndDiaryDeletionRefreshesBothAggregates() = runBlocking {
        val decisions = RoomDecisionsRepository(host, "test")
        decisions.saveCollection(CollectionDraft(id = "collection", title = "Keep history"))
        decisions.saveOption(OptionDraft(id = "option", collectionId = "collection", title = "Choice"))
        val choice = decisions.choose("collection")
        diary.save(DiaryDraft(id = "diary", title = "Linked choice", links = listOf(DiaryLink("decisionChoice", choice, "Choice", icon = "shuffle"))), "Diary")
        val collection = decisions.snapshot.first().collections.single()
        decisions.deleteCollection(collection.id, collection.revision)
        assertEquals(choice, decisions.snapshot.first().history.single().id)
        assertEquals(2, history.page(HistoryQuery(zone = zone)).entries.size)
        assertEquals(1, statistics.dates(ToolRoute.DECISION).first().size)
        statistics.dates(ToolRoute.DIARY).test {
            assertEquals(1, awaitItem().size)
            val entry = diary.snapshot.first().entries.single(); diary.delete(entry.id, entry.revision)
            assertTrue(awaitItem().isEmpty()); cancelAndIgnoreRemainingEvents()
        }
        assertEquals(ToolRoute.DECISION, history.page(HistoryQuery(zone = zone)).entries.single().route)
    }
}
