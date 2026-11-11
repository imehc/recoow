package imehc.recoow.feature.anniversaries

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import imehc.recoow.core.calendar.LunarDates
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.anniversaries.AnniversaryCategory
import imehc.recoow.core.domain.calendar.DateCalendar
import imehc.recoow.core.domain.navigation.ToolRoute
import java.time.Instant
import java.time.ZoneId

internal fun anniversaryIcon(category: AnniversaryCategory): Int = when (category) {
    AnniversaryCategory.BIRTHDAY -> CommonR.drawable.ic_anniversary_birthday
    AnniversaryCategory.RELATIONSHIP -> CommonR.drawable.ic_anniversary_relationship
    AnniversaryCategory.FAMILY -> CommonR.drawable.ic_bill_home
    AnniversaryCategory.WORK_STUDY -> CommonR.drawable.ic_bill_work
    AnniversaryCategory.PERSONAL_GOAL -> CommonR.drawable.ic_anniversary_goal
    AnniversaryCategory.OTHER -> CommonR.drawable.ic_anniversary_other
}

/** 与 iOS 分类对应，颜色从共享色板读取。 */
@Composable internal fun anniversaryTint(category: AnniversaryCategory): Color = when (category) {
    AnniversaryCategory.BIRTHDAY -> RecoowToolIcons.tint(ToolRoute.DECISION)
    AnniversaryCategory.RELATIONSHIP -> MaterialTheme.colorScheme.error
    AnniversaryCategory.FAMILY -> MaterialTheme.colorScheme.primary
    AnniversaryCategory.WORK_STUDY -> RecoowToolIcons.tint(ToolRoute.DIARY)
    AnniversaryCategory.PERSONAL_GOAL -> RecoowPalette.warningColor(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    AnniversaryCategory.OTHER -> RecoowToolIcons.tint(ToolRoute.ITEMS)
}

@Composable internal fun AnniversaryIconTile(category: AnniversaryCategory) {
    val tint = anniversaryTint(category)
    Box(Modifier.size(RecoowLayout.listCoverSize).clip(RoundedCornerShape(RecoowLayout.thumbnailRadius))
        .background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(anniversaryIcon(category)), null, tint = tint, modifier = Modifier.size(RecoowLayout.iconSize))
    }
}

@Composable internal fun anniversaryCalendarLabel(calendar: DateCalendar): String =
    stringResource(if (calendar == DateCalendar.CHINESE) R.string.ann_lunar else R.string.ann_solar)

@Composable internal fun anniversaryCountdown(daysUntil: Long?, daysSince: Long): String = when (daysUntil) {
    0L -> stringResource(R.string.ann_today)
    null -> stringResource(R.string.ann_days_since, daysSince)
    else -> stringResource(R.string.ann_days_until, daysUntil)
}

@Composable internal fun anniversaryLeadLabel(lead: Int): String =
    if (lead == 0) stringResource(R.string.ann_no_lead) else stringResource(R.string.ann_lead_minutes, lead)

/** 保留原有公历 / 农历换算与完整日期，列表取第一行，详情展示两行。 */
@Composable internal fun anniversaryDate(at: Long, primary: DateCalendar): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val lunar = LunarDates.from(at, zone)
    val moon = stringResource(R.string.ann_lunar_date, lunar.year, if (lunar.leap) stringResource(R.string.ann_leap) else "", lunar.month, lunar.day)
    val sun = stringResource(R.string.ann_solar_date, date.toString())
    return if (primary == DateCalendar.CHINESE) "$moon\n$sun" else "$sun\n$moon"
}
