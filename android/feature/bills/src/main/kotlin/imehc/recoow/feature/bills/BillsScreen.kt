package imehc.recoow.feature.bills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun BillsScreen(viewModel: BillsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val accountDraft by viewModel.accountDraft.collectAsStateWithLifecycle()
    val detail by viewModel.detailId.collectAsStateWithLifecycle()
    val accounts by viewModel.accountsOpen.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, state.bills) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { viewModel.refreshTime(); delay(viewModel.nextTick()) }
    } }
    val back: () -> Unit = {
        if (!state.busy) when { draft != null -> viewModel.cancelDraft(); accounts -> viewModel.closeAccounts(); detail != null -> viewModel.closeDetail(); else -> onBack() }
    }
    BackHandler(draft != null || accounts || detail != null || state.busy, back)
    when {
        draft != null -> BillEditor(requireNotNull(draft), state, viewModel, back)
        accounts -> AccountsScreen(state, viewModel, back)
        detail != null -> BillDetail(state.bills.firstOrNull { it.id == detail }, state, viewModel, back)
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
    val listState = rememberLazyListState()
    LaunchedEffect(searching) { if (searching) listState.scrollToItem(0) }
    val rows = remember(state.bills, state.accounts, filter, locale) {
        state.bills.filter { filter.matches(it, searchable(resources, it, state.accounts)) }
            .sortedWith(compareByDescending<BillRecord> { it.occurredAt }.thenByDescending { it.id })
    }
    val zone = remember(state.zone) { state.zone }
    val today = remember(state.now, zone) { Instant.ofEpochMilli(state.now).atZone(zone).toLocalDate() }
    val grouped = remember(rows, zone) {
        rows.groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone as ZoneId).toLocalDate() }
            .toSortedMap(compareByDescending { it })
    }
    Scaffold(Modifier.testTag("tool_bills"), containerColor = MaterialTheme.colorScheme.background, topBar = {
        RecoowCompactTopBar(
            title = stringResource(R.string.bills),
            navigationIcon = { BackButton(onBack) },
            action = {
                RecoowSearchToggle(searching, { searching = !searching }, tag = "bill_search_toggle", label = stringResource(R.string.search))
                RecoowOverflowMenu(
                    listOf(
                        RecoowAction(stringResource(R.string.filter), tag = "bill_filter", onClick = { filters = !filters }),
                        RecoowAction(stringResource(R.string.accounts), tag = "bill_accounts", onClick = viewModel::openAccounts),
                    ),
                    tag = "bills_more",
                )
                if (!state.readFailed) RecoowAddAction(stringResource(R.string.add_bill), onClick = { viewModel.edit() }, tag = "bill_add")
            },
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("bills_list"),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.sectionGap),
        ) {
            if (searching) item(key = "search") {
                RecordSearchField(filter.query, { viewModel.filter(filter.copy(query = it)) }, tag = "bill_search")
            }
            item(key = "summary") { BillTotals(state, locale) }
            item(key = "filters") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter.type == null, onClick = { viewModel.filter(filter.withType(null)) }, label = { Text(stringResource(R.string.all)) })
                    BillType.entries.forEach { type -> FilterChip(filter.type == type, onClick = { viewModel.filter(filter.withType(type)) }, label = { Text(stringResource(labelId(type.wire))) }) }
                }
            }
            if (filters) item(key = "advanced") {
                val income = filter.type == BillType.INCOME
                val options = (if (income) incomeCategories else expenseCategories).map { it to resources.getString(if (income && it == "other") R.string.other_income else labelId(it)) }
                RecoowGroup {
                    RecordChoice(stringResource(R.string.category), if (income) filter.incomeCategory else filter.category,
                        listOf(null to stringResource(R.string.all)) + options,
                        { viewModel.filter(if (income) filter.copy(incomeCategory = it) else filter.copy(category = it)) }, bordered = false)
                    RecoowDivider()
                    RecordChoice(stringResource(if (income) R.string.channel else R.string.payment), filter.payment,
                        listOf(null to stringResource(R.string.all)) + PaymentMethod.entries.map { it to resources.getString(labelId(it.wire)) },
                        { viewModel.filter(filter.copy(payment = it)) }, bordered = false)
                    RecoowDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.filter(BillFilter(type = filter.type)); filters = false }) { Text(stringResource(R.string.clear_filter)) }
                    }
                }
            }
            if (state.loading) item(key = "loading") {
                if (rows.isEmpty()) RecordSkeleton(rows = 5) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.readFailed) item(key = "retry") { RecordListFooter(failed = true, onRetry = viewModel::retry) } else if (!state.loading && rows.isEmpty()) item(key = "empty") {
                val filtered = state.bills.isNotEmpty()
                RecoowGroup {
                    RecoowEmptyState(
                        title = stringResource(if (filtered) R.string.no_matches else R.string.empty_bills),
                        actionLabel = if (filtered) stringResource(CommonR.string.record_clear_filters) else null,
                        onAction = if (filtered) ({ viewModel.filter(BillFilter()); filters = false; searching = false }) else null,
                    )
                }
            }
            grouped.forEach { (day, bills) ->
                item(key = "day:$day") {
                    Column {
                        RecoowSectionHeader(dayLabel(day, today, locale))
                        RecoowGroup {
                            bills.forEachIndexed { index, bill ->
                                BillRow(bill, state, resources, locale) { viewModel.show(bill.id) }
                                if (index < bills.lastIndex) RecoowDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun BillRow(bill: BillRecord, state: BillsState, resources: android.content.res.Resources, locale: java.util.Locale, onClick: () -> Unit) {
    val payment = if (bill.type == BillType.STORED_VALUE_USE) state.accounts.firstOrNull { it.id == bill.accountId }?.name
        ?: stringResource(R.string.stored_value_use) else stringResource(labelId(bill.payment.wire))
    val status = when {
        bill.voided(state.now) -> stringResource(R.string.refunded)
        bill.settlement == Settlement.REDEEMED -> stringResource(R.string.redeemed)
        else -> null
    }
    val subtitle = listOfNotNull(status, categoryLabel(resources, bill), payment).joinToString(" · ")
    RecoowRow(
        title = bill.title,
        subtitle = subtitle,
        trailingText = (if (bill.type == BillType.INCOME) "+" else "−") + money(bill.amounts.final, locale),
        showChevron = false,
        onClick = onClick,
        modifier = Modifier.testTag("bill_${bill.id}"),
    )
}

@Composable private fun BillTotals(state: BillsState, locale: java.util.Locale) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = remember(state.bills, state.now, state.zone) { BillSummary.calculate(state.bills, state.now, state.zone) }
    val monthMetrics = listOf(
        RecoowMetric(stringResource(R.string.month_expense), money(summary.monthExpense, locale)),
        RecoowMetric(stringResource(R.string.month_income), money(summary.monthIncome, locale)),
    )
    Column {
        // 「展开概览」放进分组标题右侧，省下一整行高度，首屏把位置让给账单列表。
        RecoowSectionHeader(
            stringResource(R.string.bills_summary),
            trailing = stringResource(if (expanded) R.string.less_summary else R.string.more_summary),
            onTrailingClick = { expanded = !expanded },
        )
        RecoowGroup {
            if (expanded) {
                RecoowMetricGrid(
                    listOf(
                        RecoowMetric(stringResource(R.string.today_expense), money(summary.todayExpense, locale)),
                        RecoowMetric(stringResource(R.string.today_income), money(summary.todayIncome, locale)),
                    ) + monthMetrics,
                )
                listOf(
                    R.string.today_discount to summary.todayDiscount,
                    R.string.today_recharge to summary.todayRecharge,
                    R.string.today_use to summary.todayUse,
                    R.string.month_discount to summary.monthDiscount,
                    R.string.month_use to summary.monthUse,
                ).forEach { (label, value) ->
                    RecoowDivider()
                    RecoowRow(title = stringResource(label), trailingText = money(value, locale))
                }
            } else {
                RecoowMetricGrid(monthMetrics)
            }
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
