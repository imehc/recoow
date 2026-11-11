package imehc.recoow.feature.anniversaries

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.anniversaries.Anniversary
import java.time.Instant
import java.time.ZoneId

@Composable internal fun AnniversaryDetail(vm: AnniversariesViewModel, state: AnniversaryState, record: Anniversary?, clock: Long, back: () -> Unit) {
    var deleting by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val names = LocalResources.current.getStringArray(R.array.ann_categories)
    val notifications by vm.notificationState.collectAsStateWithLifecycle()
    val actions = record?.let {
        listOf(
            RecoowAction(stringResource(CommonR.string.record_edit), enabled = !state.busy) { vm.edit(it) },
            RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy) { deleting = true },
        )
    }.orEmpty()
    RecordPage(stringResource(R.string.ann_details), back, tag = "anniversary_detail", busy = state.busy, more = actions, compact = true) {
        if (record == null) item {
            if (state.failed) RecordListFooter(failed = true, onRetry = vm::retry)
            else if (state.loading) RecordSkeleton(rows = 3)
            else RecoowGroup { RecoowEmptyState(stringResource(CommonR.string.record_missing)) }
        } else {
            val next = record.next(clock, zone, vm.calendar)
            val days = record.daysUntil(clock, zone, vm.calendar)
            val years = vm.calendar.yearsBetween(Instant.ofEpochMilli(record.occurredAt), Instant.ofEpochMilli(clock), record.calendar, zone)
            item(key = "head") {
                RecoowGroup {
                    Row(Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(RecoowLayout.iconTextGap)) {
                        AnniversaryIconTile(record.category)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(record.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val tint = anniversaryTint(record.category)
                                RecordBadge(names[record.category.ordinal], containerColor = tint.copy(alpha = 0.12f), contentColor = tint)
                                RecordBadge(anniversaryCalendarLabel(record.calendar))
                            }
                        }
                    }
                }
            }
            item(key = "summary") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.ann_summary))
                    RecoowGroup {
                        RecoowMetricStrip(listOf(
                            RecoowMetric(stringResource(if (days == null || days == 0L) R.string.ann_status else R.string.ann_distance),
                                if (days == 0L) stringResource(R.string.ann_today) else days?.let { stringResource(R.string.ann_days_short, it) } ?: stringResource(R.string.ann_past),
                                icon = CommonR.drawable.ic_clock),
                            RecoowMetric(stringResource(R.string.ann_elapsed), stringResource(R.string.ann_days_short, record.daysSince(clock, zone).coerceAtLeast(0)), icon = CommonR.drawable.ic_tool_anniversaries),
                            RecoowMetric(stringResource(R.string.ann_years_label), stringResource(R.string.ann_years_short, years.coerceAtLeast(0)), icon = CommonR.drawable.ic_anniversary_birthday),
                        ))
                        RecoowDivider(inset = 0.dp)
                        RecoowTextValueRow(stringResource(R.string.ann_original_date), anniversaryDate(record.occurredAt, record.calendar))
                    }
                }
            }
            item(key = "rules") {
                val fields = listOfNotNull(
                    stringResource(R.string.ann_repeat) to stringResource(if (record.yearly) R.string.ann_yearly else R.string.ann_once),
                    stringResource(R.string.ann_time_label) to stringResource(R.string.ann_time_value, record.minutes / 60, record.minutes % 60),
                    stringResource(R.string.ann_lead) to anniversaryLeadLabel(record.lead),
                    stringResource(R.string.ann_notifications) to stringResource(if (record.enabled) R.string.ann_notifications_on else R.string.ann_notifications_off),
                    next?.let { stringResource(R.string.ann_next_date) to anniversaryDate(it, record.calendar) },
                    next?.let { stringResource(R.string.ann_next_time) to recordDateTime(it) },
                )
                Column {
                    RecoowSectionHeader(stringResource(R.string.ann_rules))
                    RecoowGroup {
                        fields.forEachIndexed { index, (label, value) ->
                            RecoowTextValueRow(label, value)
                            if (index < fields.lastIndex) RecoowDivider()
                        }
                    }
                }
            }
            if (!notifications.allowed || !notifications.exact || notifications.failed) item(key = "notify") { AnniversaryNotificationControls(vm) }
            record.note?.takeIf { it.isNotBlank() }?.let { note -> item(key = "note") {
                Column {
                    RecoowSectionHeader(stringResource(CommonR.string.record_note))
                    RecoowGroup {
                        SelectionContainer { Text(note, Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
                    }
                }
            } }
        }
    }
    if (deleting && record != null) RecordDeleteConfirmation(record.title, stringResource(R.string.ann_delete_note), { deleting = false }) {
        deleting = false
        vm.delete(record)
    }
}
