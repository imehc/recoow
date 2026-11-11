package imehc.recoow.feature.food

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.bills.BillRecord
import imehc.recoow.core.domain.food.*
import imehc.recoow.core.domain.media.AttachmentKind
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.media.*
import imehc.recoow.core.media.R as MediaR
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable fun FoodScreen(vm: FoodViewModel, onBack: () -> Unit, recordId: String? = null, onBill: (String) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle(); val draft by vm.draft.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle(); val day by vm.day.collectAsStateWithLifecycle()
    val titleDraft by vm.titleDraft.collectAsStateWithLifecycle(); val pickingBills by vm.pickingBills.collectAsStateWithLifecycle()
    // 恢复的目的地不能重放其链接而丢弃正在编辑的内容。
    var deliveredRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) {
        if (recordId != null && recordId != deliveredRecordId) {
            val linkedDay = recordId.takeIf { it.startsWith("day:") }?.removePrefix("day:")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (linkedDay != null) vm.showDay(linkedDay) else vm.show(recordId)
            deliveredRecordId = recordId
        }
    }
    val nested = draft != null || detail != null || day != null || titleDraft != null || pickingBills
    val entryDetail = draft == null && titleDraft == null && !pickingBills &&
        if (recordId?.startsWith("day:") == true) detail == null && day != null else detail != null
    val back = recordBackAction(state.busy, nested, entryDetail, vm::back, onBack)
    BackHandler(nested || state.busy, back)
    when {
        pickingBills && draft != null -> FoodBillPicker(vm, state, requireNotNull(draft), back)
        titleDraft != null -> {
            val d = requireNotNull(titleDraft)
            RecordPage(stringResource(R.string.food_day_title), back, busy = state.busy, compact = true,
                action = stringResource(CommonR.string.record_save), actionIcon = CommonR.drawable.ic_check, onAction = vm::saveTitle) {
                item { RecordForm {
                    RecordFormText(stringResource(R.string.food_day_title), d.title, { vm.updateTitle(d.copy(title = it)) },
                        enabled = !state.busy, placeholder = "")
                } }
            }
        }
        draft != null -> FoodEditor(vm, state, requireNotNull(draft), back)
        detail != null -> FoodDetail(vm, state, state.data.entries.firstOrNull { it.id == detail }, back, onBill)
        day != null -> FoodDayDetail(vm, state, state.data.days(ZoneId.systemDefault()).firstOrNull { it.date.toString() == day }, LocalDate.parse(requireNotNull(day)), back)
        else -> FoodList(vm, state, back)
    }
    RecordProblem(state.error, vm::clearError)
}

@Composable private fun FoodList(vm: FoodViewModel, state: FoodState, back: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val names = LocalResources.current.getStringArray(R.array.food_meals)
    var searching by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; vm.query(""); focus.clearFocus(); keyboard?.hide() }
    BackHandler(searching, closeSearch)
    val linked = stringResource(R.string.food_linked_bills); val photos = stringResource(R.string.food_media)
    val zone = ZoneId.systemDefault()
    val groups = state.data.days(zone)
    val summary = state.data.summary(LocalDate.now(), zone)
    val filtered = groups.filter { group -> group.title.orEmpty().contains(query.trim(), true) || group.entries.any {
        listOf(it.title, names[it.meal.ordinal], it.portion.orEmpty(), it.note.orEmpty(), if (it.billIds.isNotEmpty()) linked else "", if (it.attachments.isNotEmpty()) photos else "")
            .joinToString(" ").contains(query.trim(), true)
    } }
    RecordPage(stringResource(R.string.food_title), { if (searching) closeSearch() else back() },
        tag = "tool_foodJournal", busy = state.busy, compact = true,
        searching = searching, onSearchToggle = { if (searching) closeSearch() else searching = true },
        searchTag = "food_search_toggle", searchQuery = query, onSearchQuery = vm::query,
        add = if (!state.loading && !state.failed) stringResource(R.string.food_add) else null,
        addTag = "food_add", onAdd = { vm.edit() }) {
        if (state.loading || state.failed) item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            item(key = "summary") {
                Column {
                    RecoowSectionHeader(stringResource(CommonR.string.record_overview))
                    RecoowGroup {
                        RecoowMetricStrip(listOf(
                            RecoowMetric(stringResource(R.string.food_metric_days), summary.days.toString(), icon = CommonR.drawable.ic_tool_anniversaries),
                            RecoowMetric(stringResource(R.string.food_metric_today), summary.today.toString(), icon = CommonR.drawable.ic_tool_food),
                            RecoowMetric(stringResource(R.string.food_metric_meals), summary.mealsToday.toString(), icon = CommonR.drawable.ic_metric_meals),
                            RecoowMetric(stringResource(R.string.food_latest_label), summary.latest?.let { latest ->
                                Instant.ofEpochMilli(latest).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("MM/dd"))
                            } ?: "—", icon = CommonR.drawable.ic_clock, emphasis = false),
                        ))
                        if (summary.total > 0) {
                            RecoowDivider(inset = 0.dp)
                            FoodQuickStats(summary)
                        }
                    }
                }
            }
            item(key = "days") {
                RecoowGroup {
                    RecordListHeader(stringResource(R.string.food_days_count, filtered.size),
                        Modifier.padding(horizontal = RecoowLayout.rowHorizontal))
                    RecoowDivider(inset = 0.dp)
                    if (filtered.isEmpty()) {
                        val relaxed = groups.isNotEmpty()
                        RecoowEmptyState(
                            title = stringResource(if (relaxed) CommonR.string.record_no_matches else CommonR.string.record_empty),
                            actionLabel = if (relaxed) stringResource(CommonR.string.record_clear_filters) else null,
                            onAction = if (relaxed) closeSearch else null,
                        )
                    } else filtered.forEachIndexed { index, group ->
                        FoodDayRow(group, names, vm) { vm.showDay(group.date) }
                        if (index < filtered.lastIndex) RecoowDivider(inset = RecoowLayout.coverDividerInset)
                    }
                }
            }
        }
    }
}

/** 参考 iOS 每日列表：封面、日期 / 标题、条数、餐别和关联账单数量。 */
@Composable private fun FoodDayRow(group: FoodDay, names: Array<String>, vm: FoodViewModel, onClick: () -> Unit) {
    val cover = group.entries.asSequence().flatMap { it.attachments.asSequence() }.firstOrNull { it.kind == AttachmentKind.PHOTO }
    val date = group.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    val billCount = group.entries.flatMap { it.billIds }.distinct().size
    RecordCoverRow(cover, vm.media, CommonR.drawable.ic_tool_food, RecoowToolIcons.tint(ToolRoute.FOOD), onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.title ?: date, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.food_short_entry_count, group.entries.size), style = MaterialTheme.typography.bodyMedium,
                color = RecoowToolIcons.tint(ToolRoute.FOOD))
            RecoowChevron()
        }
        if (group.title != null) Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val meals = MealKind.entries.filter { meal -> group.entries.any { it.meal == meal } }.joinToString(" · ") { names[it.ordinal] }
        Text(meals, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (billCount > 0) FoodMetadata(CommonR.drawable.ic_tool_bills, stringResource(R.string.food_bill_count, billCount))
    }
}

@Composable private fun FoodQuickStats(summary: FoodSummary) {
    val tint = RecoowToolIcons.tint(ToolRoute.FOOD)
    RecoowMetricRail(listOf(
        RecoowMetric(stringResource(R.string.food_month_snacks), stringResource(R.string.food_times, summary.snacks),
            icon = CommonR.drawable.ic_food_snack, tint = tint),
        RecoowMetric(stringResource(R.string.food_month_drinks), stringResource(R.string.food_times, summary.drinks),
            icon = CommonR.drawable.ic_food_drink, tint = tint),
        RecoowMetric(stringResource(R.string.food_month_late_night), stringResource(R.string.food_times, summary.lateNight),
            icon = CommonR.drawable.ic_food_night, tint = tint),
        RecoowMetric(stringResource(R.string.food_month_milk_tea), stringResource(R.string.food_times, summary.milkTea),
            icon = CommonR.drawable.ic_food_drink, tint = tint),
    ))
}

@Composable private fun FoodMetadata(icon: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun foodMealIcon(meal: MealKind): Int = when (meal) {
    MealKind.BREAKFAST -> CommonR.drawable.ic_food_breakfast
    MealKind.LUNCH -> CommonR.drawable.ic_tool_food
    MealKind.DINNER, MealKind.LATE_NIGHT -> CommonR.drawable.ic_food_night
    MealKind.SNACK -> CommonR.drawable.ic_food_snack
    MealKind.DRINK -> CommonR.drawable.ic_food_drink
    MealKind.OTHER -> CommonR.drawable.ic_tool_food
}

@Composable private fun foodMealTint(meal: MealKind): Color =
    RecoowPalette.foodMealColor(meal, MaterialTheme.colorScheme.background.luminance() < 0.5f)

@Composable private fun FoodBadges(meal: String?, portion: String?,
    portionColor: Color = MaterialTheme.colorScheme.surfaceContainer) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        meal?.let { RecordBadge(it, containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer) }
        portion?.takeIf { it.isNotBlank() }?.let { RecordBadge(it, containerColor = portionColor) }
    }
}

/** 单条饮食只展示核心元信息；备注在详情中阅读。 */
@Composable private fun FoodEntryRow(entry: FoodEntry, vm: FoodViewModel, meal: String, onClick: () -> Unit) {
    val photos = entry.attachments.filter { it.kind == AttachmentKind.PHOTO }
    val time = Instant.ofEpochMilli(entry.occurredAt).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    RecordCoverRow(photos.firstOrNull(), vm.media, foodMealIcon(entry.meal), foodMealTint(entry.meal), onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RecoowChevron()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FoodMetadata(foodMealIcon(entry.meal), meal)
            entry.portion?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (photos.isNotEmpty()) FoodMetadata(CommonR.drawable.ic_photo, stringResource(R.string.food_photo_count, photos.size))
            if (entry.billIds.isNotEmpty()) FoodMetadata(CommonR.drawable.ic_tool_bills, stringResource(R.string.food_bill_count, entry.billIds.size))
        }
    }
}

@Composable private fun FoodDayDetail(vm: FoodViewModel, state: FoodState, group: FoodDay?, date: LocalDate, back: () -> Unit) {
    var deleting by remember { mutableStateOf<FoodDay?>(null) }; var copying by remember { mutableStateOf<List<FoodEntry>?>(null) }
    val names = LocalResources.current.getStringArray(R.array.food_meals)
    RecordPage(group?.title ?: date.toString(), back, tag = "food_day_detail", busy = state.busy, compact = true,
        more = if (group != null) listOf(
            RecoowAction(stringResource(R.string.food_day_title), enabled = !state.busy, onClick = { vm.editTitle(group) }),
            RecoowAction(stringResource(R.string.food_delete_day), enabled = !state.busy, onClick = { deleting = group }),
        ) else emptyList(),
        add = if (!state.loading && !state.failed) stringResource(R.string.food_add) else null,
        addTag = "food_day_add", onAdd = { vm.edit() }) {
        if (group == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry)
            else RecoowEmptyState(stringResource(CommonR.string.record_empty)) }
        else {
            if (group.title != null) item { Text(group.date.toString(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            MealKind.entries.forEach { meal ->
                val entries = group.entries.filter { it.meal == meal }
                if (entries.isNotEmpty()) item(key = meal.wire) {
                    RecoowGroup {
                        RecordListHeader(names[meal.ordinal], Modifier.padding(horizontal = RecoowLayout.rowHorizontal),
                            action = {
                                IconButton(onClick = { copying = entries }, enabled = !state.busy,
                                    modifier = Modifier.size(RecoowLayout.controlSize)) {
                                    Icon(painterResource(CommonR.drawable.ic_copy), stringResource(R.string.food_copy_meal),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                                }
                            })
                        RecoowDivider(inset = 0.dp)
                        entries.forEachIndexed { index, entry ->
                            FoodEntryRow(entry, vm, names[meal.ordinal]) { vm.show(entry.id) }
                            if (index < entries.lastIndex) RecoowDivider(inset = RecoowLayout.coverDividerInset)
                        }
                    }
                }
            }
        }
    }
    copying?.let { entries -> CopyFoodDate({ copying = null }) { target -> copying = null; vm.duplicate(entries, target) } }
    deleting?.let { value -> RecordDeleteConfirmation(stringResource(R.string.food_delete_day), stringResource(R.string.food_delete_day_note, value.entries.size),
        { deleting = null }) { deleting = null; vm.deleteDay(value) } }
}

@Composable private fun FoodEditor(vm: FoodViewModel, state: FoodState, d: FoodDraft, back: () -> Unit) {
    val names = LocalResources.current.getStringArray(R.array.food_meals)
    val suggestions = state.data.suggestions(d.id)
    var attempted by rememberSaveable { mutableStateOf(false) }
    val titleError = if (attempted && !d.valid) stringResource(CommonR.string.record_field_required) else null
    RecordPage(stringResource(if (d.revision == null) R.string.food_add else R.string.food_edit), back, busy = state.busy, compact = true,
        action = stringResource(CommonR.string.record_save), actionIcon = CommonR.drawable.ic_check,
        onAction = { if (d.valid && !state.loading && !state.failed) vm.save() else attempted = true }) {
        if (suggestions.isNotEmpty()) item {
            Column {
                RecoowSectionHeader(stringResource(R.string.food_suggestions))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { suggestion ->
                        Surface(onClick = { vm.update(d.copy(title = suggestion.title, meal = suggestion.meal,
                            portion = suggestion.portion.orEmpty())) }, enabled = !state.busy,
                            shape = RoundedCornerShape(RecoowLayout.thumbnailRadius),
                            color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.widthIn(min = 112.dp, max = 160.dp)) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(painterResource(foodMealIcon(suggestion.meal)), null, tint = foodMealTint(suggestion.meal),
                                        modifier = Modifier.size(18.dp))
                                    Text(suggestion.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(listOfNotNull(suggestion.portion?.takeIf { it.isNotBlank() }, stringResource(R.string.food_times, suggestion.uses)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item {
            Column {
                RecoowSectionHeader(stringResource(R.string.food_record_info))
                RecordForm {
                    RecordFormText(stringResource(R.string.food_name), d.title, { vm.update(d.copy(title = it)) },
                        enabled = !state.busy, placeholder = "", error = titleError, tag = "food_title")
                    RecordFormDivider()
                    RecordChoice(stringResource(R.string.food_meal), d.meal, MealKind.entries.map { it to names[it.ordinal] },
                        { vm.update(d.copy(meal = it)) }, enabled = !state.busy, bordered = false)
                    RecordFormDivider()
                    RecordFormDateTime(stringResource(CommonR.string.record_date), d.occurredAt, { vm.update(d.copy(occurredAt = it)) }, !state.busy)
                }
            }
        }
        item {
            Column {
                RecoowSectionHeader(stringResource(R.string.food_portion_note))
                RecordForm {
                    RecordFormText(stringResource(R.string.food_portion), d.portion, { vm.update(d.copy(portion = it)) }, enabled = !state.busy, placeholder = "")
                    RecordFormDivider()
                    RecordFormText(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) }, enabled = !state.busy,
                        placeholder = "", multiline = true)
                }
            }
        }
        item { AttachmentInput(d.attachments, { vm.update(d.copy(attachments = it)) }, vm.media, vm.processor, state.preferences,
            sectioned = true, enabled = !state.busy, allowAudio = false) }
        item {
            Column {
                RecoowSectionHeader(stringResource(R.string.food_linked_bills), trailing = d.billIds.size.takeIf { it > 0 }?.toString())
                RecoowGroup {
                    RecoowRow(stringResource(R.string.food_select_bills), titleColor = MaterialTheme.colorScheme.primary,
                        icon = { Icon(painterResource(CommonR.drawable.ic_link), null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(RecoowLayout.iconSize)) },
                        showChevron = true, onClick = if (!state.busy) vm::pickBills else null,
                        modifier = Modifier.testTag("food_select_bills"))
                    d.billIds.forEach { id ->
                        RecoowDivider()
                        FoodBillRow(state.bills.firstOrNull { it.id == id }) {
                            IconButton(onClick = { vm.update(d.copy(billIds = d.billIds - id)) }, enabled = !state.busy,
                                modifier = Modifier.size(RecoowLayout.controlSize)) {
                                Icon(painterResource(CommonR.drawable.ic_close), stringResource(CommonR.string.record_remove),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 关联、已选及候选账单复用日记的金额颜色、元信息和固定操作区。 */
@Composable private fun FoodBillRow(bill: BillRecord?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null) {
    val resources = LocalResources.current
    RecordLinkedBillRow(bill?.title ?: stringResource(R.string.food_missing_bill),
        bill?.let { BigDecimal.valueOf(it.amounts.final, 2).toPlainString() }, bill?.type, modifier,
        subtitle = bill?.let { recordBillMetadata(it, resources) }, date = bill?.let { recordDateTime(it.occurredAt) },
        onClick = onClick, trailing = trailing)
}

@Composable private fun FoodBillPicker(vm: FoodViewModel, state: FoodState, d: FoodDraft, back: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; query = ""; focus.clearFocus(); keyboard?.hide() }
    BackHandler(searching, closeSearch)
    val resources = LocalResources.current
    val filtered = state.bills.filter { (it.title + " " + it.note.orEmpty() + " " + it.category + " " + recordBillMetadata(it, resources))
        .contains(query.trim(), true) }
    RecordPage(stringResource(R.string.food_select_bills), { if (searching) closeSearch() else back() },
        tag = "food_bill_picker", busy = state.busy, compact = true,
        searching = searching, onSearchToggle = { if (searching) closeSearch() else searching = true },
        searchTag = "food_bill_search_toggle", searchQuery = query, onSearchQuery = { query = it },
        action = stringResource(CommonR.string.record_confirm), actionIcon = CommonR.drawable.ic_check,
        onAction = { focus.clearFocus(); keyboard?.hide(); back() }) {
        item {
            RecoowGroup {
                RecordListHeader(stringResource(CommonR.string.record_bill), Modifier.padding(horizontal = RecoowLayout.rowHorizontal),
                    trailing = stringResource(R.string.food_selected_count, d.billIds.size))
                RecoowDivider(inset = 0.dp)
                when {
                    state.loading || state.failed -> RecordLoadState(state.loading, state.failed, vm::retry)
                    filtered.isEmpty() -> RecoowEmptyState(
                        title = stringResource(if (state.bills.isEmpty()) CommonR.string.record_empty else CommonR.string.record_no_matches),
                        actionLabel = if (query.isNotBlank()) stringResource(CommonR.string.record_clear_filters) else null,
                        onAction = if (query.isNotBlank()) closeSearch else null,
                    )
                    else -> filtered.forEachIndexed { index, bill ->
                        val selected = bill.id in d.billIds
                        FoodBillRow(bill, modifier = Modifier.recordToggleState(selected),
                            onClick = if (!state.busy) ({
                                vm.update(d.copy(billIds = if (selected) d.billIds - bill.id else d.billIds + bill.id))
                            }) else null) {
                            Checkbox(selected, null, modifier = Modifier.size(RecoowLayout.controlSize))
                        }
                        if (index < filtered.lastIndex) RecoowDivider()
                    }
                }
            }
        }
    }
}

@Composable private fun FoodDetail(vm: FoodViewModel, state: FoodState, entry: FoodEntry?, back: () -> Unit, onBill: (String) -> Unit) {
    var deleting by remember { mutableStateOf<FoodEntry?>(null) }; var copying by remember { mutableStateOf<FoodEntry?>(null) }
    val names = LocalResources.current.getStringArray(R.array.food_meals)
    RecordPage(entry?.title ?: stringResource(R.string.food_title), back, tag = "food_detail", busy = state.busy, compact = true,
        more = if (entry != null) listOf(
            RecoowAction(stringResource(CommonR.string.record_edit), enabled = !state.busy, onClick = { vm.edit(entry) }),
            RecoowAction(stringResource(R.string.food_copy), enabled = !state.busy, onClick = { copying = entry }),
            RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy, onClick = { deleting = entry }),
        ) else emptyList()) {
        if (entry == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry)
            else Text(stringResource(CommonR.string.record_missing)) }
        else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
                    Text(recordDateTime(entry.occurredAt), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FoodBadges(names[entry.meal.ordinal], entry.portion, portionColor = MaterialTheme.colorScheme.surface)
                }
            }
            entry.note?.takeIf { it.isNotBlank() }?.let { note ->
                item {
                    Column {
                        RecoowSectionHeader(stringResource(CommonR.string.record_note))
                        RecoowGroup {
                            SelectionContainer {
                                Text(note, Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal),
                                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
            if (entry.billIds.isNotEmpty()) item {
                Column {
                    RecoowSectionHeader(stringResource(R.string.food_linked_bills))
                    RecoowGroup {
                        entry.billIds.forEachIndexed { index, id ->
                            val bill = state.bills.firstOrNull { it.id == id }
                            FoodBillRow(bill, onClick = if (bill != null) ({ onBill(id) }) else null,
                                trailing = if (bill != null) ({ RecoowChevron() }) else null)
                            if (index < entry.billIds.lastIndex) RecoowDivider()
                        }
                    }
                }
            }
            item { AttachmentGallery(entry.attachments.filter { it.kind == AttachmentKind.PHOTO }, vm.media,
                title = stringResource(MediaR.string.attachment_photos)) }
        }
    }
    copying?.let { value -> CopyFoodDate({ copying = null }) { target -> copying = null; vm.copy(value, target) } }
    deleting?.let { value -> RecordDeleteConfirmation(value.title, stringResource(R.string.food_delete_note),
        { deleting = null }) { deleting = null; vm.delete(value) } }
}

@Composable private fun CopyFoodDate(onDismiss: () -> Unit, onDate: (LocalDate) -> Unit) {
    RecordDatePicker(LocalDate.now(), onDismiss, onDate)
}
