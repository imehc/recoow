package imehc.recoow.feature.reminders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.media.RecordCoverRow
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** 图标是用户选择的记忆内容；保留与 iOS 同步的原值，不拼进标题。 */
@Composable internal fun ReminderMemoryGlyph(icon: String) {
    if (icon.contains(".")) Icon(painterResource(when {
        icon.startsWith("calendar") -> CommonR.drawable.ic_tool_anniversaries
        icon.startsWith("house") -> CommonR.drawable.ic_bill_home
        icon.startsWith("briefcase") -> CommonR.drawable.ic_bill_work
        icon.startsWith("gift") -> CommonR.drawable.ic_bill_gift
        icon.startsWith("doc") -> CommonR.drawable.ic_bill_receipt
        icon.startsWith("wallet") -> CommonR.drawable.ic_tool_bills
        else -> CommonR.drawable.ic_tool_reminders
    }), null, tint = Color.White, modifier = Modifier.size(RecoowLayout.iconSize))
    else Text(icon.ifBlank { reminderIcons.first() }, style = MaterialTheme.typography.titleLarge, maxLines = 1)
}

@Composable internal fun reminderMemoryTint(icon: String): Color =
    if (icon.contains(".")) RecoowToolIcons.tint(ToolRoute.DIARY) else MaterialTheme.colorScheme.surfaceContainer

@Composable internal fun ReminderMemoryTile(icon: String) {
    Box(Modifier.size(RecoowLayout.listCoverSize).clip(RoundedCornerShape(RecoowLayout.thumbnailRadius))
        .background(reminderMemoryTint(icon)), contentAlignment = Alignment.Center) { ReminderMemoryGlyph(icon) }
}

@Composable internal fun ReminderStatusBadge(status: ReminderStatus) {
    val scheme = MaterialTheme.colorScheme
    val container = when (status) {
        ReminderStatus.READY, ReminderStatus.UPCOMING -> scheme.primaryContainer
        ReminderStatus.CHECKED_IN, ReminderStatus.COMPLETED -> scheme.tertiaryContainer
        ReminderStatus.BROKEN -> scheme.errorContainer
        else -> scheme.surfaceContainer
    }
    val foreground = when (status) {
        ReminderStatus.READY, ReminderStatus.UPCOMING -> scheme.onPrimaryContainer
        ReminderStatus.CHECKED_IN, ReminderStatus.COMPLETED -> scheme.onTertiaryContainer
        ReminderStatus.BROKEN -> scheme.onErrorContainer
        ReminderStatus.PAUSED, ReminderStatus.ENDED, ReminderStatus.ENDED_EARLY -> RecoowPalette.warningColor(scheme.background.luminance() < 0.5f)
        ReminderStatus.DISABLED -> scheme.onSurfaceVariant
    }
    RecordBadge(stringResource(statusLabel(status)), containerColor = container, contentColor = foreground)
}

@Composable internal fun ReminderCoverRow(r: Reminder, vm: RemindersViewModel, onClick: () -> Unit,
    modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    RecordCoverRow(locator = if (r.hasImage) MediaLocator(r.imageAssetId, "reminders", r.id) else null,
        repository = vm.media, icon = CommonR.drawable.ic_tool_reminders, tint = reminderMemoryTint(r.icon),
        iconContent = { ReminderMemoryGlyph(r.icon) }, onClick = onClick, modifier = modifier, trailing = trailing, content = content)
}

@Composable internal fun reminderTimeText(r: Reminder, state: RemindersState): String {
    val locale = LocalConfiguration.current.locales[0]
    val missed = remember(r, state.now, state.zone) { r.firstMissed(state.now, state.zone) }
    val next = remember(r, state.now, state.zone) { r.upcoming(state.now, state.zone, 1).firstOrNull() }
    return when {
        r.paused -> stringResource(R.string.reminder_paused_at, dateTime(requireNotNull(r.pausedAt), state.zone))
        r.ended -> stringResource(R.string.reminder_ended_at, dateTime(requireNotNull(r.endedAt), state.zone))
        r.completed(state.zone) -> r.completedAt?.let { stringResource(R.string.reminder_completed_at, dateTime(it, state.zone)) }
            ?: stringResource(R.string.reminder_completed)
        missed != null -> stringResource(R.string.reminder_needs_makeup, missed.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
        next != null -> stringResource(R.string.reminder_next, dateTime(next, state.zone))
        else -> stringResource(R.string.reminder_start_at, dateTime(r.scheduledAt, state.zone))
    }
}

@Composable internal fun ReminderProgressText(r: Reminder, state: RemindersState) {
    val total = r.totalDays(state.zone)
    Text(if (total == null) stringResource(R.string.reminder_total_checkins, r.totalCheckIns)
        else stringResource(R.string.reminder_short_progress, r.totalCheckIns, total),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
