package imehc.recoow.feature.reminders

import imehc.recoow.core.designsystem.RecordSwitch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(if (draft.revision == null) R.string.reminder_add else R.string.reminder_edit)) }, navigationIcon = { BackButton(onBack) },
        actions = { TextButton(onClick = vm::save, enabled = valid && enabled, modifier = Modifier.testTag("reminder_save")) { Text(stringResource(R.string.reminder_save)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("reminder_form"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { OutlinedTextField(draft.title, { vm.update(draft.copy(title = it)) }, label = { Text(stringResource(R.string.reminder_title)) }, modifier = Modifier.fillMaxWidth().testTag("reminder_title"), enabled = enabled, singleLine = true) }
            item { ChoiceField(stringResource(R.string.reminder_schedule), draft.kind, ScheduleKind.entries.map { it to resources.getString(scheduleLabel(it)) },
                { vm.update(draft.copy(kind = it, endAt = if (it == ScheduleKind.RANGE) draft.endAt ?: draft.scheduledAt else null)) }, modifier = Modifier.testTag("reminder_schedule"), enabled = enabled) }
            item { DateTimeField(stringResource(R.string.reminder_start), draft.scheduledAt, {
                val date = Instant.ofEpochMilli(it).atZone(state.zone)
                vm.update(draft.copy(scheduledAt = it, timeMinutes = date.hour * 60 + date.minute))
            }, enabled) }
            if (draft.kind == ScheduleKind.RANGE) item { DateTimeField(stringResource(R.string.reminder_end), draft.endAt, { vm.update(draft.copy(endAt = it)) }, enabled) }
            if (draft.kind == ScheduleKind.WEEKLY) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..7).forEach { day -> FilterChip(day in draft.weekdays, { vm.update(draft.copy(weekdays = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day)) },
                    label = { Text(resources.getStringArray(R.array.reminder_weekday_names)[day - 1]) }, enabled = enabled) }
            } }
            if (draft.kind in setOf(ScheduleKind.WEEKDAYS, ScheduleKind.WEEKLY, ScheduleKind.CONTINUOUS)) item {
                OutlinedTextField(draft.targetDays, { vm.update(draft.copy(targetDays = it)) }, label = { Text(stringResource(R.string.reminder_target_days)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("reminder_target"))
            }
            item { OutlinedTextField(draft.importedDays, { vm.update(draft.copy(importedDays = it)) }, label = { Text(stringResource(R.string.reminder_imported_days)) },
                supportingText = { Text(stringResource(R.string.reminder_imported_hint)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = enabled,
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("reminder_imported")) }
            item { RecordSwitch(stringResource(R.string.reminder_enabled), draft.enabled, { vm.update(draft.copy(enabled = it)) }, enabled = enabled, modifier = Modifier.testTag("reminder_enabled")) }
            item { ChoiceField(stringResource(R.string.reminder_lead), draft.lead, leadMinutes.map { it to if (it == 0) resources.getString(R.string.reminder_no_lead) else resources.getString(R.string.reminder_lead_minutes, it) },
                { vm.update(draft.copy(lead = it)) }, enabled = enabled) }
            item { ChoiceField(stringResource(R.string.reminder_icon), draft.icon, (reminderIcons + draft.icon).distinct().map { it to it }, { vm.update(draft.copy(icon = it)) }, enabled = enabled) }
            item { OutlinedTextField(draft.note, { vm.update(draft.copy(note = it)) }, label = { Text(stringResource(R.string.reminder_note)) }, enabled = enabled, minLines = 3, modifier = Modifier.fillMaxWidth()) }
            if (!valid && draft.title.isNotBlank()) item { Text(stringResource(R.string.reminder_invalid_form), color = MaterialTheme.colorScheme.error) }
            state.preferences?.let { prefs -> if (enabled) item {
                val locator = when (draft.imageEdit) {
                    ReminderImageEdit.CLEAR -> null
                    ReminderImageEdit.FILE -> draft.imageFile?.let { MediaLocator(draft = File(it)) }
                    ReminderImageEdit.ASSET -> MediaLocator(assetId = draft.imageAssetId)
                    ReminderImageEdit.KEEP -> if (draft.hasImage) MediaLocator(draft.imageAssetId, "reminders", draft.imageSourceId ?: draft.id) else null
                }
                PhotoInput(locator, { photo -> vm.update(draft.copy(imageEdit = when { photo == null -> ReminderImageEdit.CLEAR; photo.assetId != null -> ReminderImageEdit.ASSET; else -> ReminderImageEdit.FILE },
                    imageAssetId = photo?.assetId, imageFile = photo?.content?.file?.path, hasImage = photo != null)) }, vm.media, vm.processor, prefs)
            } }
        }
    }
}
