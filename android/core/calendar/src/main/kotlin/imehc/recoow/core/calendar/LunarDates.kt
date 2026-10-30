package imehc.recoow.core.calendar

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone
import java.time.*

data class LunarDate(val year: Int, val month: Int, val day: Int, val leap: Boolean)
object LunarDates {
    fun from(at: Long, zone: ZoneId): LunarDate = ChineseCalendar(TimeZone.getTimeZone(zone.id)).apply { timeInMillis = at }.let {
        LunarDate(it[Calendar.EXTENDED_YEAR] - 2637, it[Calendar.MONTH] + 1, it[Calendar.DAY_OF_MONTH], it[Calendar.IS_LEAP_MONTH] == 1)
    }
    fun resolve(date: LunarDate, zone: ZoneId): LocalDate? = runCatching {
        require(date.year in 1900..2100 && date.month in 1..12 && date.day in 1..30)
        val c = ChineseCalendar(TimeZone.getTimeZone(zone.id)).apply {
            clear(); isLenient = false; set(Calendar.EXTENDED_YEAR, date.year + 2637); set(Calendar.MONTH, date.month - 1)
            set(Calendar.IS_LEAP_MONTH, if (date.leap) 1 else 0); set(Calendar.DAY_OF_MONTH, date.day); set(Calendar.HOUR_OF_DAY, 12)
        }
        val millis = c.timeInMillis
        require(from(millis, zone) == date)
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    }.getOrNull()
    fun months(year: Int, zone: ZoneId) = (1..12).flatMap { month -> listOf(false, true).map { LunarDate(year, month, 1, it) } }.filter { resolve(it, zone) != null }
}
