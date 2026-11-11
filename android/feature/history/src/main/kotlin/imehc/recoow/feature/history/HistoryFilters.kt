package imehc.recoow.feature.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.navigation.ToolRoute
import java.time.*
import java.time.format.DateTimeFormatter

@Composable internal fun HistoryWeek(week: LocalDate, date: String?, state: HistoryState,
    onWeek: (Long) -> Unit, onDate: (LocalDate?) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.padding(horizontal = RecoowLayout.rowHorizontal).padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onWeek(-1) }, enabled = !state.busy) {
                Icon(painterResource(CommonR.drawable.ic_record_back), stringResource(R.string.history_previous_week))
            }
            Text(week.format(DateTimeFormatter.ofPattern("M/d", locale)) + " — " + week.plusDays(6).format(DateTimeFormatter.ofPattern("M/d", locale)),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { onWeek(1) }, enabled = !state.busy) {
                Icon(painterResource(CommonR.drawable.ic_chevron_right), stringResource(R.string.history_next_week))
            }
        }
        BoxWithConstraints {
            val cellWidth = (maxWidth / 7).coerceAtLeast(44.dp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                repeat(7) { index ->
                    val day = week.plusDays(index.toLong())
                    val chosen = date == day.toString()
                    val count = state.counts[day] ?: 0
                    val spoken = "$day · " + if (state.countsFailed) stringResource(R.string.history_calendar_retry) else stringResource(R.string.history_count, count)
                    Surface(onClick = { onDate(if (chosen) null else day) }, enabled = !state.busy,
                        shape = RoundedCornerShape(RecoowLayout.thumbnailRadius),
                        color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.width(cellWidth).testTag("history_day_$day").semantics {
                            contentDescription = spoken; selected = chosen
                        }) {
                        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(day.format(DateTimeFormatter.ofPattern("EEE", locale)), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyLarge,
                                color = if (chosen || day == LocalDate.now()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(if (state.countsFailed) "—" else count.toString(), style = MaterialTheme.typography.labelSmall,
                                color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HistoryFilterSheet(route: ToolRoute?, date: String?, onDismiss: () -> Unit,
    onApply: (ToolRoute?, LocalDate?) -> Unit) {
    var selectedRoute by rememberSaveable { mutableStateOf(route) }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = date?.let(LocalDate::parse)
        ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
    val names = LocalResources.current.getStringArray(CommonR.array.record_tool_names)
    RecoowBottomSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = RecoowLayout.pagePadding)) {
            RecoowSheetHeader(stringResource(R.string.history_filters))
            Text(stringResource(R.string.history_tool), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selectedRoute == null, { selectedRoute = null }, label = { Text(stringResource(R.string.history_all)) })
                ToolRoute.entries.forEach { item -> FilterChip(selectedRoute == item, { selectedRoute = item },
                    modifier = Modifier.testTag("history_filter_${item.id}"),
                    label = { Text(names[item.ordinal]) }, leadingIcon = { Icon(painterResource(RecoowToolIcons.drawable(item)), null,
                        tint = RecoowToolIcons.tint(item), modifier = Modifier.size(18.dp)) }) }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_choose_date), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(dateState.selectedDateMillis == null, { dateState.selectedDateMillis = null }, label = { Text(stringResource(R.string.history_all_dates)) })
            }
            DatePicker(dateState, title = null, headline = null, showModeToggle = false)
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedRoute = null; dateState.selectedDateMillis = null }) { Text(stringResource(CommonR.string.record_clear_filters)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(selectedRoute, dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }) },
                    modifier = Modifier.testTag("history_apply_filters")) { Text(stringResource(R.string.history_apply)) }
            }
        }
    }
}
