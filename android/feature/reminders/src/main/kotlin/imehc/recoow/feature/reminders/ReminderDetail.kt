package imehc.recoow.feature.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.media.MediaImage
import imehc.recoow.core.media.ImagePreview
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReminderDetail(r: Reminder?, state: RemindersState, vm: RemindersViewModel, onBack: () -> Unit) {
    var operation by rememberSaveable { mutableStateOf<String?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var preview by rememberSaveable { mutableStateOf(false) }
    Scaffold(Modifier.testTag("reminder_detail"), topBar = { TopAppBar(title = { Text(r?.title ?: stringResource(R.string.reminder_reminders)) }, navigationIcon = { BackButton(onBack) },
        actions = { if (r != null) TextButton(onClick = { vm.edit(r) }, enabled = !state.busy) { Text(stringResource(R.string.reminder_edit)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (r == null) item { Text(stringResource(if (state.loading) R.string.reminder_loading else R.string.reminder_missing)) }
            else {
                item { Text("${r.icon} ${r.title}", style = MaterialTheme.typography.headlineSmall) }
                item { Text(stringResource(scheduleLabel(r.kind)) + " · " + stringResource(statusLabel(r.status(state.now, state.zone)))) }
                item { Text(stringResource(R.string.reminder_start) + ": " + dateTime(r.scheduledAt, state.zone)) }
                r.endAt?.let { item { Text(stringResource(R.string.reminder_end) + ": " + dateTime(it, state.zone)) } }
                item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.reminder_total_checkins, r.totalCheckIns), style = MaterialTheme.typography.titleMedium)
                    r.totalDays(state.zone)?.let { Text(stringResource(R.string.reminder_progress_days, r.totalCheckIns, it)); Text(stringResource(R.string.reminder_remaining_days, (it - r.totalCheckIns).coerceAtLeast(0))); LinearProgressIndicator(progress = { (r.totalCheckIns.toFloat() / it).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                    Text(stringResource(R.string.reminder_streak, r.currentStreak(state.now, state.zone), r.longestStreak(state.zone)))
                    Text(stringResource(R.string.reminder_elapsed, r.elapsedDays(r.endedAt ?: state.now, state.zone)))
                    if (r.importedDays > 0) Text(stringResource(R.string.reminder_imported_count, r.importedDays))
                } } }
                r.pauseReason?.let { item { Text(stringResource(R.string.reminder_pause_reason) + ": " + it) } }
                r.endReason?.let { item { Text(stringResource(R.string.reminder_end_reason) + ": " + it) } }
                r.note?.let { item { Text(it) } }
                if (r.hasImage) item { MediaImage(MediaLocator(r.imageAssetId, "reminders", r.id), vm.media, Modifier.fillMaxWidth().height(240.dp).clickable { preview = true }) }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.canCheckIn(state.now, state.zone)) Button(onClick = { vm.act(r, ReminderAction.CHECK_IN) }, enabled = !state.busy, modifier = Modifier.testTag("reminder_checkin")) { Text(stringResource(R.string.reminder_check_in)) }
                    val today = Instant.ofEpochMilli(state.now).atZone(state.zone).toLocalDate()
                    if (today in r.dates && r.occurrence(today, state.zone) != null) OutlinedButton(onClick = { operation = ReminderAction.UNDO_TODAY.name }, enabled = !state.busy) { Text(stringResource(R.string.reminder_undo)) }
                    if (r.firstMissed(state.now, state.zone) != null) FilledTonalButton(onClick = { vm.makeup(r) }, enabled = !state.busy, modifier = Modifier.testTag("reminder_makeup")) { Text(stringResource(R.string.reminder_make_up)) }
                    if (r.canRestore(state.zone)) OutlinedButton(onClick = { operation = ReminderAction.RESTORE.name }, enabled = !state.busy) { Text(stringResource(R.string.reminder_restore)) }
                    if (!r.completed(state.zone)) {
                        if (r.paused) OutlinedButton(onClick = { vm.act(r, ReminderAction.RESUME) }, enabled = !state.busy) { Text(stringResource(R.string.reminder_resume)) }
                        else if (r.kind == ScheduleKind.GOAL) OutlinedButton(onClick = { operation = ReminderAction.PAUSE.name; reason = "" }, enabled = !state.busy) { Text(stringResource(R.string.reminder_pause)) }
                        if (r.kind in setOf(ScheduleKind.GOAL, ScheduleKind.CONTINUOUS)) OutlinedButton(onClick = { operation = ReminderAction.END_EARLY.name; reason = "" }, enabled = !state.busy) { Text(stringResource(R.string.reminder_end_early)) }
                    }
                    OutlinedButton(onClick = { vm.duplicate(r) }, enabled = !state.busy) { Text(stringResource(R.string.reminder_duplicate)) }
                    OutlinedButton(onClick = { operation = "delete" }, enabled = !state.busy) { Text(stringResource(R.string.reminder_delete)) }
                } }
                item { Text(stringResource(R.string.reminder_checkin_history), style = MaterialTheme.typography.titleMedium) }
                if (r.completions.isEmpty()) item { Text(stringResource(R.string.reminder_no_checkins)) }
                items(r.completions.sortedByDescending { it.date }, key = { it.date.toString() }) { c -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(c.date.toString() + " · " + stringResource(if (c.kind == CheckInKind.MAKE_UP) R.string.reminder_make_up else R.string.reminder_check_in))
                    Text(dateTime(c.completedAt, state.zone), style = MaterialTheme.typography.bodySmall)
                    c.note?.let { Text(it) }
                    TextButton(onClick = { operation = "date:${c.date}" }, enabled = !state.busy) { Text(stringResource(R.string.reminder_delete_record)) }
                } } }
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
