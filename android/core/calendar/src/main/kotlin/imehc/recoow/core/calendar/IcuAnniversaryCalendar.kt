package imehc.recoow.core.calendar

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import android.icu.util.GregorianCalendar
import android.icu.util.TimeZone
import imehc.recoow.core.domain.calendar.AnniversaryCalendar
import imehc.recoow.core.domain.calendar.AnniversarySchedule
import imehc.recoow.core.domain.calendar.CalendarDate
import imehc.recoow.core.domain.calendar.DateCalendar
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** 对齐 AnniversaryRecord.swift 中的发生实例构造方式与逐年相加顺序。 */
class IcuAnniversaryCalendar : AnniversaryCalendar {
    private fun calendar(kind: DateCalendar, zone: ZoneId): Calendar {
        val timeZone = TimeZone.getTimeZone(zone.id)
        return when (kind) {
            DateCalendar.GREGORIAN -> GregorianCalendar(timeZone)
            DateCalendar.CHINESE -> ChineseCalendar(timeZone)
        }.apply {
            isLenient = true
            repeatedWallTimeOption = Calendar.WALLTIME_FIRST
        }
    }

    override fun date(at: Instant, calendar: DateCalendar, zone: ZoneId): CalendarDate =
        calendar(calendar, zone).apply { timeInMillis = at.toEpochMilli() }.let {
            CalendarDate(it[Calendar.ERA], it[Calendar.YEAR], it[Calendar.MONTH] + 1,
                it[Calendar.DAY_OF_MONTH], it[Calendar.IS_LEAP_MONTH] == 1)
        }

    override fun yearsBetween(from: Instant, to: Instant, calendar: DateCalendar, zone: ZoneId): Int {
        if (to < from) return 0
        return calendar(calendar, zone).apply { timeInMillis = from.toEpochMilli() }.fieldDifference(java.util.Date(to.toEpochMilli()), Calendar.YEAR).coerceAtLeast(0)
    }

    override fun nextOccurrence(schedule: AnniversarySchedule, reference: Instant, zone: ZoneId): Instant? {
        val localTime = LocalTime.of(schedule.reminderTimeMinutes / 60, schedule.reminderTimeMinutes % 60)
        val localDateTime = schedule.occurredAt.atZone(zone).toLocalDate().atTime(localTime)
        // Foundation 的 bySettingHour 在夏令时空档处取 nextTime，即空档后的第一个有效时刻。
        val base = if (zone.rules.getValidOffsets(localDateTime).isEmpty()) {
            requireNotNull(zone.rules.getTransition(localDateTime)).dateTimeAfter.atZone(zone).toInstant()
        } else localDateTime.atZone(zone).toInstant()
        if (!schedule.yearly) return base.takeIf { it >= reference }
        if (reference < base) return base

        val original = date(schedule.occurredAt, schedule.calendar, zone)
        val current = date(reference, schedule.calendar, zone)
        val occurrence = calendar(schedule.calendar, zone).apply {
            clear()
            set(Calendar.ERA, current.era)
            set(Calendar.YEAR, current.year)
            set(Calendar.MONTH, original.month - 1)
            set(Calendar.IS_LEAP_MONTH, if (original.leapMonth) 1 else 0)
            set(Calendar.DAY_OF_MONTH, original.day)
            set(Calendar.HOUR_OF_DAY, localTime.hour)
            set(Calendar.MINUTE, localTime.minute)
        }
        if (Instant.ofEpochMilli(occurrence.timeInMillis) < reference) occurrence.add(Calendar.YEAR, 1)
        return Instant.ofEpochMilli(occurrence.timeInMillis)
    }
}
