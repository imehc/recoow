package imehc.recoow.feature.reminders

import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.media.PhotoInput
import java.io.File
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ReminderEditor(draft: ReminderDraft, state: RemindersState, vm: RemindersViewModel, onBack: () -> Unit) {
    val resources = LocalResources.current
    val previous = state.records.firstOrNull { it.id == draft.id }
    val valid = draft.valid(previous, state.now, state.zone)
    val enabled = !state.busy
    var attempted by rememberSaveable { mutableStateOf(false) }
    val titleError = if (attempted && draft.title.isBlank()) stringResource(CommonR.string.record_field_required) else null
    val targetError = if (attempted && draft.targetDays.toLongOrNull()?.let { it in 1..9999 } != true) stringResource(R.string.reminder_target_error) else null
    val total = draft.record(previous, state.zone)?.totalDays(state.zone)
        ?: when (draft.kind) {
            ScheduleKind.SINGLE -> 1L
            ScheduleKind.RANGE -> draft.endAt?.let { java.time.temporal.ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(draft.scheduledAt).atZone(state.zone).toLocalDate(), Instant.ofEpochMilli(it).atZone(state.zone).toLocalDate()) + 1 }
            ScheduleKind.GOAL -> 9999L
            else -> draft.targetDays.toLongOrNull()
        }
    val importedError = if (attempted && (draft.importedDays.toLongOrNull()?.let { it >= 0 && (total == null || it <= total) } != true)) stringResource(R.string.reminder_imported_error) else null
    val endError = if (attempted && draft.kind == ScheduleKind.RANGE && draft.endAt?.let {
        Instant.ofEpochMilli(it).atZone(state.zone).toLocalDate() >= Instant.ofEpochMilli(draft.scheduledAt).atZone(state.zone).toLocalDate()
    } != true) stringResource(R.string.reminder_end_error) else null
    val weekdayError = if (attempted && draft.kind == ScheduleKind.WEEKLY && draft.weekdays.isEmpty()) stringResource(R.string.reminder_weekdays_error) else null
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        RecoowCompactTopBar(title = stringResource(if (draft.revision == null) R.string.reminder_add else R.string.reminder_edit), navigationIcon = { BackButton(onBack) }, action = {
            IconButton(onClick = { if (valid) vm.save() else attempted = true }, enabled = enabled, modifier = Modifier.testTag("reminder_save")) {
                Icon(painterResource(CommonR.drawable.ic_check), stringResource(R.string.reminder_save), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("reminder_form"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                RecordFormSection(stringResource(R.string.reminder_record_info)) {
                    RecordFormText(stringResource(R.string.reminder_title), draft.title, { vm.update(draft.copy(title = it)) },
                        enabled = enabled, placeholder = "", error = titleError, tag = "reminder_title")
                    RecordFormDivider()
                    ReminderIconPickerRow(draft.icon, { vm.update(draft.copy(icon = it)) }, enabled = enabled)
                }
            }
            item {
                RecordFormSection(stringResource(R.string.reminder_schedule_section)) {
                    RecordChoice(stringResource(R.string.reminder_schedule), draft.kind,
                        ScheduleKind.entries.map { it to resources.getString(scheduleLabel(it)) },
                        { vm.update(draft.copy(kind = it, endAt = if (it == ScheduleKind.RANGE) draft.endAt ?: draft.scheduledAt else null)) },
                        enabled = enabled, bordered = false, modifier = Modifier.testTag("reminder_schedule"))
                    RecordFormDivider()
                    RecordFormDateTime(stringResource(R.string.reminder_start), draft.scheduledAt, {
                        val date = Instant.ofEpochMilli(it).atZone(state.zone)
                        vm.update(draft.copy(scheduledAt = it, timeMinutes = date.hour * 60 + date.minute))
                    }, enabled)
                    if (draft.kind == ScheduleKind.RANGE) {
                        RecordFormDivider()
                        RecordFormDateTime(stringResource(R.string.reminder_end), draft.endAt, { vm.update(draft.copy(endAt = it)) }, enabled)
                        endError?.let { RecordFormHint(it) }
                    }
                    if (draft.kind == ScheduleKind.WEEKLY) {
                        RecordFormDivider()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            (1..7).forEach { day -> FilterChip(day in draft.weekdays, { vm.update(draft.copy(weekdays = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day)) },
                                label = { Text(resources.getStringArray(R.array.reminder_weekday_names)[day - 1]) }, enabled = enabled) }
                        }
                        weekdayError?.let { RecordFormHint(it) }
                    }
                    if (draft.kind in setOf(ScheduleKind.WEEKDAYS, ScheduleKind.WEEKLY, ScheduleKind.CONTINUOUS)) {
                        RecordFormDivider()
                        RecordFormText(stringResource(R.string.reminder_target_days), draft.targetDays,
                            { vm.update(draft.copy(targetDays = it)) }, enabled = enabled, keyboard = KeyboardType.Number, error = targetError, tag = "reminder_target")
                    }
                    RecordFormDivider()
                    RecordFormText(stringResource(R.string.reminder_imported_days), draft.importedDays,
                        { vm.update(draft.copy(importedDays = it)) }, enabled = enabled, keyboard = KeyboardType.Number, error = importedError, tag = "reminder_imported")
                    RecordFormDivider()
                    RecordFormHint(stringResource(R.string.reminder_imported_hint))
                }
            }
            item {
                RecordFormSection(stringResource(R.string.reminder_notification_section)) {
                    RecordChoice(stringResource(R.string.reminder_lead), draft.lead,
                        leadMinutes.map { it to if (it == 0) resources.getString(R.string.reminder_no_lead) else resources.getString(R.string.reminder_lead_minutes, it) },
                        { vm.update(draft.copy(lead = it)) }, enabled = enabled, bordered = false)
                    RecordFormDivider()
                    RecordSwitch(stringResource(R.string.reminder_enabled), draft.enabled, { vm.update(draft.copy(enabled = it)) },
                        enabled = enabled, modifier = Modifier.padding(horizontal = RecoowLayout.rowHorizontal).testTag("reminder_enabled"))
                }
            }
            item {
                RecordFormSection(stringResource(R.string.reminder_note)) {
                    RecordFormText(stringResource(R.string.reminder_note), draft.note, { vm.update(draft.copy(note = it)) },
                        enabled = enabled, placeholder = "", multiline = true)
                }
            }
            if (attempted && !valid && titleError == null && targetError == null && importedError == null && endError == null && weekdayError == null) item {
                RecoowNotice(stringResource(R.string.reminder_invalid_form))
            }
            state.preferences?.let { prefs -> if (enabled) item {
                val locator = when (draft.imageEdit) {
                    ReminderImageEdit.CLEAR -> null
                    ReminderImageEdit.FILE -> draft.imageFile?.let { MediaLocator(draft = File(it)) }
                    ReminderImageEdit.ASSET -> MediaLocator(assetId = draft.imageAssetId)
                    ReminderImageEdit.KEEP -> if (draft.hasImage) MediaLocator(draft.imageAssetId, "reminders", draft.imageSourceId ?: draft.id) else null
                }
                PhotoInput(locator, { photo -> vm.update(draft.copy(imageEdit = when { photo == null -> ReminderImageEdit.CLEAR; photo.assetId != null -> ReminderImageEdit.ASSET; else -> ReminderImageEdit.FILE },
                    imageAssetId = photo?.assetId, imageFile = photo?.content?.file?.path, hasImage = photo != null)) }, vm.media, vm.processor, prefs, compact = true)
            } }
        }
    }
}
