package imehc.recoow.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.history.HistoryEntry
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.media.RecordBillCoverRow
import imehc.recoow.core.media.RecordCoverRow
import java.math.BigInteger
import java.text.NumberFormat

@Composable internal fun HistoryRow(entry: HistoryEntry, vm: HistoryViewModel, state: HistoryState, onRecord: (ToolRoute, String) -> Unit) {
    val appearance by remember(entry, vm) { vm.appearance(entry) }.collectAsStateWithLifecycle(HistoryAppearance())
    val names = LocalResources.current.getStringArray(CommonR.array.record_tool_names)
    val tint = RecoowToolIcons.tint(entry.route)
    var menu by remember { mutableStateOf(false) }
    val selected = entry.key in state.selected
    val modifier = Modifier.testTag("history_${entry.key}")
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
        .then(if (state.selecting) Modifier.recordToggleState(selected) else Modifier)
    val click = { if (state.selecting) vm.select(entry) else onRecord(entry.route, entry.recordId) }
    val longClick = { if (!state.selecting) menu = true }
    val trailing: @Composable () -> Unit = {
        Box(Modifier.size(RecoowLayout.controlSize), contentAlignment = Alignment.Center) {
            if (state.selecting) Checkbox(selected, onCheckedChange = null, enabled = !state.busy)
            else {
                IconButton(onClick = { menu = true }, enabled = !state.busy) {
                    Icon(painterResource(CommonR.drawable.ic_more), stringResource(CommonR.string.record_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(RecoowLayout.cardRadius),
                    containerColor = MaterialTheme.colorScheme.surface) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_select_many)) }, enabled = !state.busy,
                        onClick = { menu = false; vm.selecting(); vm.select(entry) })
                    DropdownMenuItem(text = { Text(stringResource(CommonR.string.record_delete), color = MaterialTheme.colorScheme.error) }, enabled = !state.busy,
                        onClick = { menu = false; vm.prepare(listOf(entry)) })
                }
            }
        }
    }
    val bill = appearance.bill
    if (bill != null) {
        val currency = NumberFormat.getCurrencyInstance(LocalConfiguration.current.locales[0]).apply { currency = java.util.Currency.getInstance("CNY") }
        RecordBillCoverRow(bill, vm.media, currency.format(BigInteger.valueOf(bill.amounts.final).toBigDecimal(2)), click,
            modifier, trailing, enabled = !state.busy, onLongClick = longClick)
        return
    }
    val thumbnail: @Composable () -> Unit = {
        val customIcon = appearance.icon
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(RecoowLayout.thumbnailRadius)).background(tint.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            if (!customIcon.isNullOrBlank()) Text(customIcon, style = MaterialTheme.typography.titleLarge)
            else Icon(painterResource(RecoowToolIcons.drawable(entry.route)), null, tint = tint, modifier = Modifier.size(RecoowLayout.iconSize))
        }
    }
    val content: @Composable ColumnScope.() -> Unit = {
        Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(recordDateTime(entry.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val subtitle = appearance.subtitle?.takeIf { it.isNotBlank() }
            ?: entry.detail.takeIf { entry.route in setOf(ToolRoute.ITEMS, ToolRoute.DECISION, ToolRoute.DIARY) && it.isNotBlank() }
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (entry.route == ToolRoute.DIARY) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RecordBadge(names[entry.route.ordinal], containerColor = tint.copy(alpha = .1f), contentColor = tint)
            appearance.mood?.let { mood -> RecordBadge(stringResource(when (mood.ordinal) {
                0 -> R.string.history_mood_neutral; 1 -> R.string.history_mood_happy; 2 -> R.string.history_mood_calm
                3 -> R.string.history_mood_tired; 4 -> R.string.history_mood_stressed; else -> R.string.history_mood_sad
            }), containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) }
            appearance.tags.take(2).forEach { RecordBadge(if (entry.route == ToolRoute.DIARY) "# $it" else it) }
        }
        if (entry.route == ToolRoute.FOOD) Text(stringResource(R.string.history_food_day, entry.day.toString(), entry.count),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (vm.media != null) RecordCoverRow(appearance.image, vm.media, RecoowToolIcons.drawable(entry.route), tint.copy(alpha = .12f),
        click, modifier, iconContent = thumbnail, trailing = trailing, enabled = !state.busy, onLongClick = longClick, content = content)
    else RecordThumbnailRow(click, modifier, thumbnail, trailing, enabled = !state.busy, onLongClick = longClick, content = content)
}
