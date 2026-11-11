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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import java.time.Instant
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
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
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
    val back = recordBackAction(state.busy, draft != null || detail != null || makeup != null,
        entryDetail = draft == null && makeup == null && detail != null, onNestedBack = viewModel::back, onBack = onBack)
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
@Composable private fun ReminderList(state: RemindersState, tab: Int, notifications: ReminderNotificationState, vm: RemindersViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.settle() }
    val query by vm.query.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    var searching by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; vm.query(""); focus.clearFocus(); keyboard?.hide() }
    BackHandler(searching) { closeSearch() }
    val source = vm.rows(state, tab)
    val rows = remember(source, query, state.now, state.zone, resources) { source.filter { r ->
        listOf(r.title, r.note.orEmpty(), resources.getString(scheduleLabel(r.kind)),
            resources.getString(statusLabel(r.status(state.now, state.zone)))).joinToString(" ").contains(query.trim(), ignoreCase = true)
    } }
    val today = Instant.ofEpochMilli(state.now).atZone(state.zone).toLocalDate()
    Scaffold(Modifier.testTag("tool_reminders"), containerColor = MaterialTheme.colorScheme.background,
        topBar = { RecoowCompactTopBar(stringResource(R.string.reminder_reminders), navigationIcon = { BackButton(onBack) },
            titleContent = { RecordSearchTitle(stringResource(R.string.reminder_reminders), searching, query, vm::query, "reminder_search") },
            action = { RecoowSearchToggle(searching, { if (searching) closeSearch() else searching = true }, tag = "reminder_search_toggle") }) },
        floatingActionButton = { if (!state.failed) RecoowFloatingAddAction(stringResource(R.string.reminder_add), onClick = { vm.edit() }, enabled = !state.busy, tag = "reminder_add") }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding().testTag("reminder_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap),
        ) {
            item(key = "summary") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.reminder_summary))
                    RecoowGroup {
                        RecoowMetricStrip(listOf(
                            RecoowMetric(stringResource(R.string.reminder_metric_active), state.records.count { !it.completed(state.zone) && !it.paused }.toString(), icon = CommonR.drawable.ic_tool_reminders),
                            RecoowMetric(stringResource(R.string.reminder_metric_ready), state.records.count { it.canCheckIn(state.now, state.zone) }.toString(), icon = CommonR.drawable.ic_clock),
                            RecoowMetric(stringResource(R.string.reminder_metric_today), state.records.count { today in it.dates }.toString(), icon = CommonR.drawable.ic_check),
                            RecoowMetric(stringResource(R.string.reminder_metric_completed), state.records.count { it.completed(state.zone) }.toString(), icon = CommonR.drawable.ic_metric_streak),
                        ))
                    }
                }
            }
            if (!notifications.allowed || !notifications.exact || notifications.failed) item(key = "notifications") { NotificationControls(notifications, vm) }
            item(key = "rows") {
                if (state.loading && rows.isEmpty()) RecordSkeleton(rows = 4, leading = true)
                else RecoowGroup {
                    RecordListHeader(stringResource(R.string.reminder_task_count, rows.size), Modifier.padding(horizontal = RecoowLayout.rowHorizontal))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = RecoowLayout.rowHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(R.string.reminder_active, R.string.reminder_paused, R.string.reminder_history).forEachIndexed { index, label ->
                            FilterChip(tab == index, { vm.tab(index) }, label = { Text(stringResource(label)) })
                        }
                    }
                    RecoowDivider(inset = 0.dp)
                    if (state.failed) RecordListFooter(failed = true, onRetry = vm::retry)
                    else if (rows.isEmpty()) {
                        val filtered = source.isNotEmpty() && query.isNotBlank()
                        RecoowEmptyState(
                            title = stringResource(if (filtered) CommonR.string.record_no_matches else when (tab) {
                                1 -> R.string.reminder_empty_paused; 2 -> R.string.reminder_empty_history; else -> R.string.reminder_empty_active
                            }),
                            description = stringResource(if (filtered) CommonR.string.record_no_matches_hint else when (tab) {
                                1 -> R.string.reminder_empty_paused_hint; 2 -> R.string.reminder_empty_history_hint; else -> R.string.reminder_empty_active_hint
                            }),
                            actionLabel = if (filtered) stringResource(CommonR.string.record_clear_filters) else null,
                            onAction = if (filtered) closeSearch else null,
                        )
                    } else rows.forEachIndexed { index, r ->
                        ReminderListRow(r, state, vm)
                        if (index < rows.lastIndex) RecoowDivider(inset = RecoowLayout.coverDividerInset)
                    }
                }
            }
            if (state.busy || state.loading && rows.isNotEmpty()) item(key = "busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
    }
}

@Composable private fun ReminderListRow(r: Reminder, state: RemindersState, vm: RemindersViewModel) {
    ReminderCoverRow(r, vm, onClick = { vm.show(r.id) }, modifier = Modifier.testTag("reminder_${r.id}"), trailing = {
        Box(Modifier.size(RecoowLayout.controlSize), contentAlignment = Alignment.Center) {
            if (r.canCheckIn(state.now, state.zone)) FilledTonalIconButton(onClick = { vm.act(r, ReminderAction.CHECK_IN) },
                enabled = !state.busy, modifier = Modifier.testTag("reminder_checkin_${r.id}")) {
                Icon(painterResource(CommonR.drawable.ic_check), stringResource(R.string.reminder_check_in), modifier = Modifier.size(20.dp))
            } else RecoowChevron()
        }
    }) {
        Text(r.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            val status = r.status(state.now, state.zone)
            if (status != ReminderStatus.READY && status != ReminderStatus.CHECKED_IN) ReminderStatusBadge(status)
            ReminderProgressText(r, state)
        }
        Text(stringResource(scheduleLabel(r.kind)) + " · " + reminderTimeText(r, state), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val total = r.totalDays(state.zone)
        if (total != null) LinearProgressIndicator(progress = { (r.totalCheckIns.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}
@Composable internal fun NotificationControls(state: ReminderNotificationState, vm: RemindersViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshNotifications() }
    if (!state.allowed || !state.exact || state.failed) RecoowNotice(
        text = stringResource(if (!state.allowed) R.string.reminder_notifications_off else if (state.failed) R.string.reminder_notification_failed else R.string.reminder_inexact),
        actions = buildList {
            if (!state.allowed) add(RecoowAction(stringResource(R.string.reminder_enable_notifications)) {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            })
            add(RecoowAction(stringResource(R.string.reminder_notification_settings)) {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            })
            if (Build.VERSION.SDK_INT >= 31 && !state.exact) add(RecoowAction(stringResource(R.string.reminder_exact_settings)) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            })
        },
    )
}
