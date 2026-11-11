package imehc.recoow.feature.anniversaries

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.anniversaries.*
import java.time.*
import kotlinx.coroutines.delay

@Composable fun AnniversariesScreen(vm: AnniversariesViewModel, onBack: () -> Unit, recordId: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var delivered by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) { if (recordId != null && delivered != recordId) { vm.show(recordId); delivered = recordId } }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { clock = System.currentTimeMillis(); delay(1000) } } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshNotifications() }
    val back = recordBackAction(state.busy, draft != null || detail != null,
        entryDetail = draft == null && detail != null, onNestedBack = vm::back, onBack = onBack)
    BackHandler(draft != null || detail != null || state.busy, back)
    when {
        draft != null -> AnniversaryEditor(vm, state, requireNotNull(draft), back)
        detail != null -> AnniversaryDetail(vm, state, state.records.firstOrNull { it.id == detail }, clock, back)
        else -> AnniversaryList(vm, state, clock, back)
    }
    RecordProblem(state.error, vm::clearError)
}

@Composable private fun AnniversaryList(vm: AnniversariesViewModel, state: AnniversaryState, clock: Long, back: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val notifications by vm.notificationState.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val names = LocalResources.current.getStringArray(R.array.ann_categories)
    var searching by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; vm.query(""); focus.clearFocus(); keyboard?.hide() }
    BackHandler(searching) { closeSearch() }
    val rows = state.records.filter { (it.title + " " + it.note.orEmpty() + " " + names[it.category.ordinal]).contains(query.trim(), true) }
    val next = remember(state.records, clock, zone) { state.records.associate { it.id to it.next(clock, zone, vm.calendar) } }
    val today = Instant.ofEpochMilli(clock).atZone(zone).toLocalDate()
    val upcomingDays = next.values.filterNotNull().map { java.time.temporal.ChronoUnit.DAYS.between(today, Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) }
    RecordPage(stringResource(R.string.ann_title), back, tag = "tool_anniversaries", busy = state.busy, compact = true,
        searching = searching, onSearchToggle = { if (searching) closeSearch() else searching = true }, searchTag = "anniversary_search_toggle",
        searchQuery = query, onSearchQuery = vm::query,
        add = if (!state.loading && !state.failed) stringResource(R.string.ann_add) else null, addTag = "anniversary_add", onAdd = { vm.edit() }) {
        if (state.failed) item { RecordListFooter(failed = true, onRetry = vm::retry) }
        else if (state.loading && state.records.isEmpty()) item { RecordSkeleton(rows = 4, leading = true) }
        else {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item(key = "summary") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.ann_summary))
                    RecoowGroup {
                        RecoowMetricStrip(listOf(
                            RecoowMetric(stringResource(R.string.ann_metric_total), state.records.size.toString(), icon = CommonR.drawable.ic_tool_anniversaries),
                            RecoowMetric(stringResource(R.string.ann_today), upcomingDays.count { it == 0L }.toString(), icon = CommonR.drawable.ic_anniversary_birthday),
                            RecoowMetric(stringResource(R.string.ann_metric_soon), upcomingDays.count { it in 0L..30L }.toString(), icon = CommonR.drawable.ic_clock),
                            RecoowMetric(stringResource(R.string.ann_past), next.values.count { it == null }.toString(), icon = CommonR.drawable.ic_record_back),
                        ))
                    }
                }
            }
            if (!notifications.allowed || !notifications.exact || notifications.failed) item { AnniversaryNotificationControls(vm) }
            if (rows.isEmpty()) item {
                val filtered = state.records.isNotEmpty() && query.isNotBlank()
                RecoowGroup {
                    RecoowEmptyState(
                        title = stringResource(if (filtered) CommonR.string.record_no_matches else R.string.ann_empty),
                        description = stringResource(if (filtered) CommonR.string.record_no_matches_hint else R.string.ann_empty_hint),
                        actionLabel = if (filtered) stringResource(CommonR.string.record_clear_filters) else null,
                        onAction = if (filtered) closeSearch else null,
                    )
                }
            }
            for (past in listOf(false, true)) {
                val group = rows.filter { (next[it.id] == null) == past }.let {
                    if (past) it.sortedByDescending { r -> r.occurredAt } else it.sortedBy { r -> next[r.id] }
                }
                if (group.isNotEmpty()) item(key = "group:$past") {
                    Column {
                        RecoowSectionHeader(stringResource(if (past) R.string.ann_past else R.string.ann_upcoming),
                            trailing = stringResource(R.string.ann_record_count, group.size))
                        RecoowGroup {
                            group.forEachIndexed { index, record ->
                                AnniversaryListRow(vm, record, next[record.id], clock, zone, names[record.category.ordinal])
                                if (index < group.lastIndex) RecoowDivider(inset = RecoowLayout.coverDividerInset)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun AnniversaryListRow(vm: AnniversariesViewModel, record: Anniversary, next: Long?, clock: Long, zone: ZoneId, category: String) {
    val days = record.daysUntil(clock, zone, vm.calendar)
    RecordThumbnailRow(onClick = { vm.show(record.id) }, modifier = Modifier.testTag("anniversary_${record.id}"),
        thumbnail = { AnniversaryIconTile(record.category) }, trailingAlignment = Alignment.Top,
        trailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(anniversaryCountdown(days, record.daysSince(clock, zone)), Modifier.widthIn(max = 112.dp),
                    style = MaterialTheme.typography.bodyLarge, color = anniversaryTint(record.category),
                    textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(RecoowLayout.controlSize), contentAlignment = Alignment.Center) { RecoowChevron() }
            }
        }) {
        Text(record.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(if (next != null) R.string.ann_next_occurrence else R.string.ann_occurred_on,
            anniversaryDate(next ?: record.occurredAt, record.calendar).substringBefore("\n")),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val tint = anniversaryTint(record.category)
            RecordBadge(category, containerColor = tint.copy(alpha = 0.12f), contentColor = tint)
            RecordBadge(anniversaryCalendarLabel(record.calendar))
            if (record.yearly) RecordBadge(stringResource(R.string.ann_yearly))
            if (!record.enabled) RecordBadge(stringResource(R.string.ann_notifications_off))
        }
    }
}

@Composable internal fun AnniversaryNotificationControls(vm: AnniversariesViewModel) {
    val state by vm.notificationState.collectAsStateWithLifecycle(); val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshNotifications() }
    if (!state.allowed || !state.exact || state.failed) RecoowNotice(
        text = stringResource(if (!state.allowed) R.string.ann_access_off else if (state.failed) R.string.ann_access_failed else R.string.ann_access_inexact),
        actions = buildList {
            add(RecoowAction(stringResource(R.string.ann_access_settings)) {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            })
            if (Build.VERSION.SDK_INT >= 31 && !state.exact) add(RecoowAction(stringResource(R.string.ann_access_exact)) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            })
        },
    )
}
