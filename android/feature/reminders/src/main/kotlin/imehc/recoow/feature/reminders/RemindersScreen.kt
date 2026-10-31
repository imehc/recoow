package imehc.recoow.feature.reminders

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import androidx.lifecycle.repeatOnLifecycle
import imehc.recoow.core.domain.reminders.*
import kotlinx.coroutines.delay

internal fun scheduleLabel(kind: ScheduleKind) = when (kind) {
    ScheduleKind.SINGLE -> R.string.reminder_single; ScheduleKind.RANGE -> R.string.reminder_range; ScheduleKind.WEEKDAYS -> R.string.reminder_weekdays
    ScheduleKind.WEEKLY -> R.string.reminder_weekly; ScheduleKind.CONTINUOUS -> R.string.reminder_continuous; ScheduleKind.GOAL -> R.string.reminder_goal
}
internal fun statusLabel(status: ReminderStatus) = when (status) {
    ReminderStatus.READY -> R.string.reminder_ready; ReminderStatus.UPCOMING -> R.string.reminder_upcoming; ReminderStatus.CHECKED_IN -> R.string.reminder_checked_in
    ReminderStatus.COMPLETED -> R.string.reminder_completed; ReminderStatus.PAUSED -> R.string.reminder_paused; ReminderStatus.DISABLED -> R.string.reminder_disabled
    ReminderStatus.BROKEN -> R.string.reminder_broken; ReminderStatus.ENDED -> R.string.reminder_ended; ReminderStatus.ENDED_EARLY -> R.string.reminder_ended_early
}
@Composable fun RemindersScreen(viewModel: RemindersViewModel, onBack: () -> Unit, recordId: String? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val detail by viewModel.detailId.collectAsStateWithLifecycle()
    val makeup by viewModel.makeupId.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val notifications by viewModel.notificationState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptic = LocalHapticFeedback.current
    var deliveredRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) {
        if (recordId != null && recordId != deliveredRecordId) {
            viewModel.show(recordId)
            deliveredRecordId = recordId
        }
    }
    LaunchedEffect(state.feedback) { if (state.feedback > 0) haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshNotifications() }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { viewModel.refreshTime(); delay(30_000) } } }
    val back: () -> Unit = { if (!state.busy) { if (draft != null || detail != null || makeup != null) viewModel.back() else onBack() } }
    BackHandler(draft != null || detail != null || makeup != null || state.busy, back)
    when {
        makeup != null -> CheckInCalendar(state.records.firstOrNull { it.id == makeup }, state, viewModel, back)
        draft != null -> ReminderEditor(requireNotNull(draft), state, viewModel, back)
        detail != null -> ReminderDetail(state.records.firstOrNull { it.id == detail }, state, viewModel, back)
        else -> ReminderList(state, tab, notifications, viewModel, back)
    }
    state.error?.let { AlertDialog(onDismissRequest = viewModel::clearError, text = { Text(stringResource(it)) },
        confirmButton = { TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.reminder_confirm)) } }) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReminderList(state: RemindersState, tab: Int, notifications: ReminderNotificationState, vm: RemindersViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.settle() }
    Scaffold(Modifier.testTag("tool_reminders"), topBar = { TopAppBar(title = { Text(stringResource(R.string.reminder_reminders)) }, navigationIcon = { BackButton(onBack) }) },
        floatingActionButton = { if (!state.failed) ExtendedFloatingActionButton(onClick = { vm.edit() }, modifier = Modifier.testTag("reminder_add")) { Text(stringResource(R.string.reminder_add)) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("reminder_list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { NotificationControls(notifications, vm) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(R.string.reminder_active, R.string.reminder_paused, R.string.reminder_history).forEachIndexed { index, label ->
                FilterChip(tab == index, { vm.tab(index) }, label = { Text(stringResource(label)) })
            } } }
            if (state.loading) item { CircularProgressIndicator() }
            else if (state.failed) item { TextButton(onClick = vm::retry) { Text(stringResource(R.string.reminder_retry)) } }
            else if (vm.rows(state, tab).isEmpty()) item { Text(stringResource(R.string.reminder_empty)) }
            items(vm.rows(state, tab), key = { it.id }) { r -> Card(Modifier.fillMaxWidth().clickable { vm.show(r.id) }.testTag("reminder_${r.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${r.icon} ${r.title}", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(scheduleLabel(r.kind)) + " · " + stringResource(statusLabel(r.status(state.now, state.zone))))
                    Text(dateTime(r.scheduledAt, state.zone), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.reminder_total_checkins, r.totalCheckIns))
                    r.totalDays(state.zone)?.let { total -> LinearProgressIndicator(progress = { (r.totalCheckIns.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                    if (r.canCheckIn(state.now, state.zone)) FilledTonalButton(onClick = { vm.act(r, ReminderAction.CHECK_IN) }, enabled = !state.busy) { Text(stringResource(R.string.reminder_check_in)) }
                }
            } }
        }
    }
}
@Composable internal fun NotificationControls(state: ReminderNotificationState, vm: RemindersViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshNotifications() }
    if (!state.allowed || !state.exact || state.failed) Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (!state.allowed) R.string.reminder_notifications_off else if (state.failed) R.string.reminder_notification_failed else R.string.reminder_inexact), style = MaterialTheme.typography.bodyMedium)
        FlowRow {
            if (!state.allowed) TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text(stringResource(R.string.reminder_enable_notifications)) }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text(stringResource(R.string.reminder_notification_settings)) }
            if (Build.VERSION.SDK_INT >= 31 && !state.exact) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text(stringResource(R.string.reminder_exact_settings)) }
        }
    } }
}
