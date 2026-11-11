package imehc.recoow.feature.reminders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.media.RecordPhotoSection
import imehc.recoow.core.media.ImagePreview
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReminderDetail(r: Reminder?, state: RemindersState, vm: RemindersViewModel, onBack: () -> Unit) {
    var operation by rememberSaveable { mutableStateOf<String?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var preview by rememberSaveable { mutableStateOf(false) }
    val today = Instant.ofEpochMilli(state.now).atZone(state.zone).toLocalDate()
    val locale = LocalConfiguration.current.locales[0]
    val actions = if (r == null) emptyList() else buildList {
        add(RecoowAction(stringResource(R.string.reminder_edit), enabled = !state.busy) { vm.edit(r) })
        if (today in r.dates && r.occurrence(today, state.zone) != null)
            add(RecoowAction(stringResource(R.string.reminder_undo), enabled = !state.busy) { operation = ReminderAction.UNDO_TODAY.name })
        if (r.firstMissed(state.now, state.zone) != null)
            add(RecoowAction(stringResource(R.string.reminder_make_up), tag = "reminder_makeup", enabled = !state.busy) { vm.makeup(r) })
        if (r.canRestore(state.zone))
            add(RecoowAction(stringResource(R.string.reminder_restore), enabled = !state.busy) { operation = ReminderAction.RESTORE.name })
        if (!r.completed(state.zone)) {
            if (r.paused) add(RecoowAction(stringResource(R.string.reminder_resume), enabled = !state.busy) { vm.act(r, ReminderAction.RESUME) })
            else if (r.kind == ScheduleKind.GOAL) add(RecoowAction(stringResource(R.string.reminder_pause), enabled = !state.busy) { operation = ReminderAction.PAUSE.name; reason = "" })
            if (r.kind in setOf(ScheduleKind.GOAL, ScheduleKind.CONTINUOUS))
                add(RecoowAction(stringResource(R.string.reminder_end_early), enabled = !state.busy) { operation = ReminderAction.END_EARLY.name; reason = "" })
        }
        add(RecoowAction(stringResource(R.string.reminder_duplicate), enabled = !state.busy) { vm.duplicate(r) })
        add(RecoowAction(stringResource(R.string.reminder_delete), enabled = !state.busy) { operation = "delete" })
    }
    Scaffold(Modifier.testTag("reminder_detail"), containerColor = MaterialTheme.colorScheme.background,
        topBar = { RecoowCompactTopBar(stringResource(R.string.reminder_details), navigationIcon = { BackButton(onBack) },
            action = { RecoowOverflowMenu(actions, tag = "reminder_detail_more") }) },
        bottomBar = { if (r?.canCheckIn(state.now, state.zone) == true) RecoowBottomAction(stringResource(R.string.reminder_check_in),
            onClick = { vm.act(r, ReminderAction.CHECK_IN) }, enabled = !state.busy, modifier = Modifier.testTag("reminder_checkin")) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
            if (r == null) item {
                if (state.loading) RecordSkeleton(rows = 3) else RecoowGroup { RecoowEmptyState(stringResource(R.string.reminder_missing)) }
            } else {
                item(key = "head") {
                    RecoowGroup {
                        Row(Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(RecoowLayout.iconTextGap)) {
                            ReminderMemoryTile(r.icon)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(r.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                ReminderStatusBadge(r.status(state.now, state.zone))
                            }
                        }
                    }
                }
                if (state.busy) item(key = "busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item(key = "progress") {
                    Column {
                        RecoowSectionHeader(stringResource(R.string.reminder_progress_section))
                        RecoowGroup {
                            RecoowMetricStrip(listOf(
                                RecoowMetric(stringResource(R.string.reminder_metric_total), r.totalCheckIns.toString(), icon = CommonR.drawable.ic_check),
                                RecoowMetric(stringResource(R.string.reminder_metric_streak), r.currentStreak(state.now, state.zone).toString(), icon = CommonR.drawable.ic_metric_streak),
                                RecoowMetric(stringResource(R.string.reminder_metric_longest), r.longestStreak(state.zone).toString(), icon = CommonR.drawable.ic_metric_streak),
                                RecoowMetric(stringResource(R.string.reminder_metric_elapsed), r.elapsedDays(r.endedAt ?: state.now, state.zone).toString(), icon = CommonR.drawable.ic_tool_anniversaries),
                            ))
                            r.totalDays(state.zone)?.let { total ->
                                RecoowDivider(inset = 0.dp)
                                RecoowRow(stringResource(R.string.reminder_progress_label), trailingText = stringResource(R.string.reminder_progress_days, r.totalCheckIns, total),
                                    subtitle = stringResource(R.string.reminder_remaining_days, (total - r.totalCheckIns).coerceAtLeast(0)))
                                LinearProgressIndicator(progress = { (r.totalCheckIns.toFloat() / total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal).padding(bottom = 12.dp))
                            }
                            if (r.importedDays > 0) {
                                RecoowDivider(inset = 0.dp)
                                RecoowRow(stringResource(R.string.reminder_imported_days), trailingText = r.importedDays.toString())
                            }
                        }
                    }
                }
                item(key = "schedule") {
                    val next = r.upcoming(state.now, state.zone, 1).firstOrNull()
                    val weekdays = androidx.compose.ui.platform.LocalResources.current.getStringArray(R.array.reminder_weekday_names)
                    val details = listOfNotNull(
                        stringResource(R.string.reminder_schedule) to stringResource(scheduleLabel(r.kind)),
                        stringResource(R.string.reminder_start) to dateTime(r.scheduledAt, state.zone),
                        r.endAt?.let { stringResource(R.string.reminder_end) to dateTime(it, state.zone) },
                        if (r.kind == ScheduleKind.WEEKLY) stringResource(R.string.reminder_weekly_days) to r.weekdays.sorted().joinToString(" · ") { weekdays[it - 1] } else null,
                        next?.let { stringResource(R.string.reminder_next_reminder) to dateTime(it, state.zone) },
                        stringResource(R.string.reminder_lead) to if (r.lead == 0) stringResource(R.string.reminder_no_lead) else stringResource(R.string.reminder_lead_minutes, r.lead),
                        r.pausedAt?.takeIf { r.paused }?.let { stringResource(R.string.reminder_paused) to dateTime(it, state.zone) },
                        r.pauseReason?.let { stringResource(R.string.reminder_pause_reason) to it },
                        r.endedAt?.let { stringResource(R.string.reminder_ended_early) to dateTime(it, state.zone) },
                        r.endReason?.let { stringResource(R.string.reminder_end_reason) to it },
                    )
                    Column {
                        RecoowSectionHeader(stringResource(R.string.reminder_schedule_section))
                        RecoowGroup {
                            details.forEachIndexed { index, (label, value) ->
                                RecoowTextValueRow(label, value)
                                if (index < details.lastIndex) RecoowDivider()
                            }
                        }
                    }
                }
                r.note?.takeIf { it.isNotBlank() }?.let { note -> item(key = "note") {
                    Column {
                        RecoowSectionHeader(stringResource(R.string.reminder_note))
                        RecoowGroup {
                            SelectionContainer { Text(note, Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                } }
                if (r.hasImage) item(key = "image") { RecordPhotoSection(MediaLocator(r.imageAssetId, "reminders", r.id), vm.media) { preview = true } }
                val months = r.completions.sortedByDescending { it.date }.groupBy { YearMonth.from(it.date) }
                if (months.isEmpty()) item(key = "history-empty") {
                    Column {
                        RecoowSectionHeader(stringResource(R.string.reminder_checkin_history))
                        RecoowGroup { RecoowEmptyState(stringResource(R.string.reminder_no_checkins)) }
                    }
                } else months.forEach { (month, completions) -> item(key = "history:$month") {
                    Column {
                        RecoowSectionHeader(stringResource(R.string.reminder_checkin_history), trailing = month.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM", locale)))
                        RecoowGroup {
                            completions.forEachIndexed { index, c ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal, vertical = RecoowLayout.rowVertical),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(c.date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)),
                                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                            RecordBadge(stringResource(if (c.kind == CheckInKind.MAKE_UP) R.string.reminder_make_up else R.string.reminder_check_in))
                                        }
                                        Text(dateTime(c.completedAt, state.zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        c.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                                    }
                                    IconButton(onClick = { operation = "date:${c.date}" }, enabled = !state.busy) {
                                        Icon(painterResource(CommonR.drawable.ic_close), stringResource(R.string.reminder_delete_record), modifier = Modifier.size(20.dp))
                                    }
                                }
                                if (index < completions.lastIndex) RecoowDivider()
                            }
                        }
                    }
                } }
            }
        }
    }
    if (r != null && preview) ImagePreview(listOf(MediaLocator(r.imageAssetId, "reminders", r.id)), 0, vm.media) { preview = false }
    if (r != null) operation?.let { op ->
        val needsReason = op in setOf(ReminderAction.PAUSE.name, ReminderAction.END_EARLY.name)
        AlertDialog(onDismissRequest = { operation = null }, title = { Text(stringResource(R.string.reminder_confirm_action)) },
            text = { if (needsReason) OutlinedTextField(reason, { reason = it }, label = { Text(stringResource(R.string.reminder_reason)) }, modifier = Modifier.testTag("reminder_reason"))
                else Text(stringResource(if (op == ReminderAction.RESTORE.name) R.string.reminder_restore_hint else R.string.reminder_delete_hint)) },
            confirmButton = { TextButton(enabled = !state.busy && (!needsReason || reason.isNotBlank()), onClick = {
                when { op == "delete" -> vm.delete(r); op.startsWith("date:") -> vm.deleteCompletion(r, LocalDate.parse(op.removePrefix("date:"))); else -> vm.act(r, ReminderAction.valueOf(op), reason) }; operation = null
            }) { Text(stringResource(R.string.reminder_confirm)) } }, dismissButton = { TextButton(onClick = { operation = null }) { Text(stringResource(R.string.reminder_cancel)) } })
    }
}
