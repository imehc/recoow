package imehc.recoow.feature.bills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.media.RecordCoverRow
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun BillsScreen(viewModel: BillsViewModel, onBack: () -> Unit, recordId: String? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val accountDraft by viewModel.accountDraft.collectAsStateWithLifecycle()
    val detail by viewModel.detailId.collectAsStateWithLifecycle()
    val accounts by viewModel.accountsOpen.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val focusedDetail = detail ?: recordId
    var deliveredRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) {
        if (recordId != null && recordId != deliveredRecordId) { viewModel.show(recordId); deliveredRecordId = recordId }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, state.bills) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { viewModel.refreshTime(); delay(viewModel.nextTick()) }
    } }
    val back: () -> Unit = {
        if (!state.busy) when {
            draft != null -> viewModel.cancelDraft()
            accounts -> viewModel.closeAccounts()
            focusedDetail != null -> { viewModel.closeDetail(); if (recordId != null) onBack() }
            else -> onBack()
        }
    }
    BackHandler(draft != null || accounts || detail != null || state.busy || recordId != null, back)
    when {
        draft != null -> BillEditor(requireNotNull(draft), state, viewModel, back)
        accounts -> AccountsScreen(state, viewModel, back)
        focusedDetail != null -> BillDetail(state.bills.firstOrNull { it.id == focusedDetail }, state, viewModel, back)
        else -> BillsList(state, filter, viewModel, back)
    }
    accountDraft?.let { AccountEditor(it, state.busy, viewModel) }
    state.error?.let { message -> AlertDialog(onDismissRequest = viewModel::clearError,
        text = { Text(stringResource(message)) }, confirmButton = { TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.confirm)) } }) }
}

@Composable private fun BillsList(state: BillsState, filter: BillFilter, viewModel: BillsViewModel, onBack: () -> Unit) {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    var filters by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; viewModel.filter(filter.copy(query = "")); focus.clearFocus(); keyboard?.hide() }
    BackHandler(searching) { closeSearch() }
    val listState = rememberLazyListState()
    val rows = remember(state.bills, state.accounts, filter, locale) {
        state.bills.filter { filter.matches(it, searchable(resources, it, state.accounts)) }
            .sortedWith(compareByDescending<BillRecord> { it.occurredAt }.thenByDescending { it.id })
    }
    val zone = state.zone
    val today = remember(state.now, zone) { Instant.ofEpochMilli(state.now).atZone(zone).toLocalDate() }
    val grouped = remember(rows, zone) {
        rows.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
            .toSortedMap(compareByDescending { it })
    }
    val advancedLabels = listOfNotNull(
        filter.category?.let { resources.getString(labelId(it)) },
        filter.incomeCategory?.let { resources.getString(if (it == "other") R.string.other_income else labelId(it)) },
        filter.payment?.let { resources.getString(labelId(it.wire)) },
    )
    Scaffold(Modifier.testTag("tool_bills"), containerColor = MaterialTheme.colorScheme.background, topBar = {
        RecoowCompactTopBar(
            title = stringResource(R.string.bills),
            titleContent = { RecordSearchTitle(stringResource(R.string.bills), searching, filter.query,
                { viewModel.filter(filter.copy(query = it)) }, tag = "bill_search") },
            navigationIcon = { BackButton(onBack) },
            action = {
                RecoowSearchToggle(searching, { if (searching) closeSearch() else searching = true }, tag = "bill_search_toggle", label = stringResource(R.string.search))
                RecoowOverflowMenu(listOf(
                    RecoowAction(stringResource(R.string.accounts), tag = "bill_accounts", onClick = viewModel::openAccounts),
                ), tag = "bills_more")
            },
        )
    }, floatingActionButton = {
        if (!state.readFailed) RecoowFloatingAddAction(stringResource(R.string.add_bill), onClick = { viewModel.edit() }, enabled = !state.busy, tag = "bill_add")
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding().testTag("bills_list"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap),
        ) {
            item(key = "summary") { BillTotals(state, locale) }
            item(key = "filters") {
                RecoowGroup {
                    RecordListHeader(stringResource(R.string.bill_count, rows.size), Modifier.padding(horizontal = RecoowLayout.rowHorizontal), action = {
                        IconButton(onClick = { filters = true }, modifier = Modifier.testTag("bill_filter")) {
                            Icon(painterResource(CommonR.drawable.ic_filter), stringResource(R.string.filter),
                                tint = if (advancedLabels.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = RecoowLayout.rowHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(filter.type == null, onClick = { viewModel.filter(filter.withType(null)) }, label = { Text(stringResource(R.string.all)) })
                        BillType.entries.forEach { type -> FilterChip(filter.type == type, onClick = { viewModel.filter(filter.withType(type)) }, label = { Text(stringResource(labelId(type.wire))) }) }
                    }
                    if (advancedLabels.isNotEmpty()) Text(advancedLabels.joinToString(" · "),
                        Modifier.padding(start = RecoowLayout.rowHorizontal, end = RecoowLayout.rowHorizontal, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.loading) item(key = "loading") {
                if (rows.isEmpty()) RecordSkeleton(rows = 5, leading = true) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.readFailed) item(key = "retry") { RecordListFooter(failed = true, onRetry = viewModel::retry) }
            else if (!state.loading && rows.isEmpty()) item(key = "empty") {
                val filtered = state.bills.isNotEmpty()
                RecoowGroup {
                    RecoowEmptyState(
                        title = stringResource(if (filtered) R.string.no_matches else R.string.empty_bills),
                        description = stringResource(if (filtered) CommonR.string.record_no_matches_hint else R.string.empty_bills_info),
                        actionLabel = if (filtered) stringResource(CommonR.string.record_clear_filters) else null,
                        onAction = if (filtered) ({ viewModel.filter(BillFilter()); filters = false; searching = false; focus.clearFocus(); keyboard?.hide() }) else null,
                    )
                }
            }
            grouped.forEach { (day, bills) ->
                item(key = "day:$day") {
                    Column {
                        RecoowSectionHeader(dayLabel(day, today, locale))
                        RecoowGroup {
                            bills.forEachIndexed { index, bill ->
                                BillRow(bill, state, resources, locale, viewModel) { viewModel.show(bill.id) }
                                if (index < bills.lastIndex) RecoowDivider(inset = RecoowLayout.coverDividerInset)
                            }
                        }
                    }
                }
            }
        }
    }
    if (filters) BillFilterSheet(filter, { filters = false }) { selected ->
        viewModel.filter(selected); filters = false
        if (selected.query.isEmpty()) { searching = false; focus.clearFocus(); keyboard?.hide() }
    }
}

/** 与日记相同的筛选面板；分类与支付共用弹层，应用后再改变列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BillFilterSheet(filter: BillFilter, onDismiss: () -> Unit, onApply: (BillFilter) -> Unit) {
    val resources = LocalResources.current
    var selected by rememberSaveable { mutableStateOf(filter) }
    val income = selected.type == BillType.INCOME
    RecoowBottomSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = RecoowLayout.pagePadding),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
            RecoowSheetHeader(stringResource(R.string.filter))
            Text(stringResource(R.string.bill_type), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected.type == null, { selected = selected.withType(null) }, label = { Text(stringResource(R.string.all)) })
                BillType.entries.forEach { type -> FilterChip(selected.type == type, { selected = selected.withType(type) }, label = { Text(stringResource(labelId(type.wire))) }) }
            }
            RecordForm {
                RecordChoice(stringResource(R.string.category), if (income) selected.incomeCategory else selected.category,
                    listOf(null to stringResource(R.string.all)) + (if (income) incomeCategories else expenseCategories).map {
                        it to resources.getString(if (income && it == "other") R.string.other_income else labelId(it))
                    }, { selected = if (income) selected.copy(incomeCategory = it) else selected.copy(category = it) }, bordered = false)
                RecordFormDivider()
                RecordChoice(stringResource(if (income) R.string.channel else R.string.payment), selected.payment,
                    listOf(null to stringResource(R.string.all)) + PaymentMethod.entries.map { it to resources.getString(labelId(it.wire)) },
                    { selected = selected.copy(payment = it) }, bordered = false)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = BillFilter(type = selected.type) }) { Text(stringResource(R.string.clear_filter)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onApply(selected) }, modifier = Modifier.testTag("bill_apply_filters")) { Text(stringResource(R.string.apply_filters)) }
            }
        }
    }
}

@Composable private fun BillRow(bill: BillRecord, state: BillsState, resources: android.content.res.Resources,
    locale: java.util.Locale, viewModel: BillsViewModel, onClick: () -> Unit) {
    val payment = if (bill.type == BillType.STORED_VALUE_USE) state.accounts.firstOrNull { it.id == bill.accountId }?.name
        ?: stringResource(R.string.stored_value_use) else stringResource(labelId(bill.payment.wire))
    val status = when {
        bill.voided(state.now) -> stringResource(R.string.refunded)
        bill.settlement == Settlement.REDEEMED -> stringResource(R.string.redeemed)
        bill.canRedeem(state.now) -> stringResource(R.string.pending_redemption)
        else -> null
    }
    val subtitle = listOfNotNull(resources.getString(labelId(bill.type.wire)), categoryLabel(resources, bill), payment, status).distinct().joinToString(" · ")
    RecordCoverRow(locator = if (bill.hasImage) MediaLocator(bill.imageAssetId, "bills", bill.id) else null,
        repository = viewModel.media, icon = recordBillIcon(bill.type, bill.category), tint = recordBillTint(bill.type),
        onClick = onClick, modifier = Modifier.testTag("bill_${bill.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(bill.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            RecoowMoneyValue(money(bill.amounts.final, locale), bill.type)
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun BillTotals(state: BillsState, locale: java.util.Locale) {
    val summary = remember(state.bills, state.now, state.zone) { BillSummary.calculate(state.bills, state.now, state.zone) }
    Column {
        RecoowSectionHeader(stringResource(R.string.bills_summary))
        RecoowGroup {
            RecoowMetricStrip(listOf(
                RecoowMetric(stringResource(R.string.today_expense), money(summary.todayExpense, locale), icon = CommonR.drawable.ic_bill_receipt),
                RecoowMetric(stringResource(R.string.today_income), money(summary.todayIncome, locale), icon = CommonR.drawable.ic_tool_bills),
            ))
            RecoowDivider(inset = 0.dp)
            RecoowMetricRail(listOf(
                RecoowMetric(stringResource(R.string.month_expense), money(summary.monthExpense, locale), icon = CommonR.drawable.ic_bill_receipt),
                RecoowMetric(stringResource(R.string.month_income), money(summary.monthIncome, locale), icon = CommonR.drawable.ic_tool_bills),
                RecoowMetric(stringResource(R.string.today_discount), money(summary.todayDiscount, locale), icon = CommonR.drawable.ic_bill_gift),
                RecoowMetric(stringResource(R.string.today_recharge), money(summary.todayRecharge, locale), icon = CommonR.drawable.ic_tool_bills),
                RecoowMetric(stringResource(R.string.today_use), money(summary.todayUse, locale), icon = CommonR.drawable.ic_tool_bills),
                RecoowMetric(stringResource(R.string.month_discount), money(summary.monthDiscount, locale), icon = CommonR.drawable.ic_bill_gift),
                RecoowMetric(stringResource(R.string.month_use), money(summary.monthUse, locale), icon = CommonR.drawable.ic_tool_bills),
            ))
        }
    }
}

/** 账单按天分组，标题只给「今天」以外的日期，避免同一天重复出现两次日期。 */
private fun dayLabel(day: LocalDate, today: LocalDate, locale: java.util.Locale): String {
    val pattern = when {
        locale.language == "zh" && day.year == today.year -> "M月d日 E"
        locale.language == "zh" -> "yyyy年M月d日"
        day.year == today.year -> "EEE, MMM d"
        else -> "MMM d, yyyy"
    }
    return day.format(DateTimeFormatter.ofPattern(pattern, locale))
}
