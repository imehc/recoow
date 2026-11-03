package imehc.recoow.core.domain

import imehc.recoow.core.domain.diary.*
import imehc.recoow.core.domain.food.*
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.records.NamedRecord
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class JournalRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 30)
    private fun time(day: LocalDate) = day.atTime(12, 34, 56).atZone(zone).toInstant().toEpochMilli()
    private fun diary(day: LocalDate, tags: List<DiaryTagReference> = emptyList()) = DiaryEntry(day.toString(), "r", "Diary", "Content", DiaryMood.CALM, time(day), tags, emptyList(), emptyList())
    private fun food(id: String, title: String = "Food", day: LocalDate = today, meal: MealKind = MealKind.LUNCH, portion: String? = null) =
        FoodEntry(id, "r", title, meal, time(day), portion, "Keep note", listOf("bill"), emptyList())

    @Test fun diaryStreakStartsYesterdayAndDeletedTagSnapshotRemainsReadable() {
        val tag = DiaryTagReference("tag", "Old name")
        val data = DiarySnapshot(listOf(diary(today.minusDays(1), listOf(tag)), diary(today.minusDays(2)), diary(today.plusDays(1))), listOf(NamedRecord("tag", "r", "New name")))
        assertEquals(DiarySummary(3, 0, 3, 2), data.summary(today, zone))
        assertEquals("New name", data.resolvedTags(data.entries.first()).single().value)
        assertEquals(tag, data.copy(tags = emptyList()).tagReferences().single())
        assertFalse(DiaryDraft().valid); assertTrue(DiaryDraft(content = "正文").valid)
    }
    @Test fun foodGroupingKeepsWholeDayAndStableTimestampIdentity() {
        val timestamp = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val record = FoodDayRecord(foodDayId(timestamp), "r", timestamp, "Day title")
        val data = FoodSnapshot(listOf(food("b"), food("a")), listOf(record))
        val group = data.days(zone).single()
        assertEquals("food-day:1793289600000", record.id)
        assertEquals(listOf("a", "b"), group.entries.map { it.id }); assertEquals("Day title", group.title)
        assertNotEquals(record.id, foodDayId(today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
    }
    @Test fun monthlyAndMilkTeaCountsUseTheirExactBoundaries() {
        val data = FoodSnapshot(listOf(food("a", "MILK TEA", meal = MealKind.DRINK), food("b", "奶茶", day = today.withDayOfMonth(1)),
            food("c", day = today.withDayOfMonth(25), meal = MealKind.SNACK), food("d", day = today.withDayOfMonth(26), meal = MealKind.SNACK),
            food("e", day = today.withDayOfMonth(31), meal = MealKind.LATE_NIGHT), food("f", "milk tea", today.plusMonths(1)), food("g", "奶茶", day = today.minusMonths(1))))
        val summary = data.summary(today, zone)
        assertEquals(2, summary.snacks); assertEquals(1, summary.drinks); assertEquals(1, summary.lateNight); assertEquals(2, summary.milkTea)
    }
    @Test fun suggestionsRankFrequencyAndKeepMealAndPortionWithoutCopyingNotes() {
        val data = FoodSnapshot(listOf(food("a", " Noodles ", portion = "Bowl"), food("b", "noodles", today.minusDays(1), portion = "bowl"), food("c", "Toast", portion = "2")))
        val first = data.suggestions().first(); assertEquals("Noodles", first.title); assertEquals(2, first.uses); assertEquals("Bowl", first.portion)
        assertEquals(1, data.suggestions("a").first { it.title == "noodles" }.uses)
        assertEquals(listOf(MealKind.LATE_NIGHT, MealKind.BREAKFAST, MealKind.LUNCH, MealKind.SNACK, MealKind.DINNER, MealKind.LATE_NIGHT), listOf(4, 5, 10, 15, 18, 22).map(MealKind::at))
    }
    @Test fun copiesKeepWallClockSecondsAndClearAllRelationshipsIncludingAudio() {
        val source = food("source").copy(attachments = listOf(MediaAttachment(kind = AttachmentKind.AUDIO, mimeType = "audio/mp4")))
        val copy = FoodDraft.duplicate(source, today.plusDays(2), zone)
        assertNotEquals(source.id, copy.id); assertNull(copy.revision); assertTrue(copy.billIds.isEmpty()); assertTrue(copy.attachments.isEmpty())
        assertEquals(time(today.plusDays(2)), copy.occurredAt); assertEquals(source.note, copy.note)
        val ny = ZoneId.of("America/New_York")
        val before = LocalDate.of(2026, 3, 7).atTime(2, 30, 45).atZone(ny).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 3, 8).atTime(3, 0).atZone(ny).toInstant().toEpochMilli(), copiedFoodTime(before, LocalDate.of(2026, 3, 8), ny))
    }
}
