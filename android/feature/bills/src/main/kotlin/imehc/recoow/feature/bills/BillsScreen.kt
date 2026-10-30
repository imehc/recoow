package imehc.recoow.feature.bills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import imehc.recoow.core.domain.bills.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BillsList(state: BillsState, filter: BillFilter, viewModel: BillsViewModel, onBack: () -> Unit) {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    var filters by rememberSaveable { mutableStateOf(false) }
    val rows = remember(state.bills, state.accounts, filter, locale) { state.bills.filter { filter.matches(it, searchable(resources, it, state.accounts)) } }
    Scaffold(Modifier.testTag("tool_bills"), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.bills)) }, navigationIcon = { BackButton(onBack) },
            actions = { IconButton(onClick = viewModel::openAccounts, modifier = Modifier.testTag("bill_accounts")) {
                Icon(painterResource(R.drawable.ic_accounts), contentDescription = stringResource(R.string.accounts))
            } })
    }, floatingActionButton = {
        if (!state.readFailed) ExtendedFloatingActionButton(onClick = { viewModel.edit() }, modifier = Modifier.testTag("bill_add")) { Text(stringResource(R.string.add_bill)) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("bills_list"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { BillTotals(state) }
            item { OutlinedTextField(filter.query, { viewModel.filter(filter.copy(query = it)) }, label = { Text(stringResource(R.string.search)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bill_search")) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter.type == null, onClick = { viewModel.filter(filter.withType(null)) }, label = { Text(stringResource(R.string.all)) })
                BillType.entries.forEach { type -> FilterChip(filter.type == type, onClick = { viewModel.filter(filter.withType(type)) }, label = { Text(stringResource(labelId(type.wire))) }) }
                FilterChip(filters || filter.category != null || filter.incomeCategory != null || filter.payment != null, onClick = { filters = !filters }, label = { Text(stringResource(R.string.filter)) })
            } }
            if (filters) item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val income = filter.type == BillType.INCOME
                val options = (if (income) incomeCategories else expenseCategories).map { it to resources.getString(if (income && it == "other") R.string.other_income else labelId(it)) }
                ChoiceField(stringResource(R.string.category), if (income) filter.incomeCategory else filter.category,
                    listOf(null to stringResource(R.string.all)) + options, { viewModel.filter(if (income) filter.copy(incomeCategory = it) else filter.copy(category = it)) })
                ChoiceField(stringResource(if (income) R.string.channel else R.string.payment), filter.payment,
                    listOf(null to stringResource(R.string.all)) + PaymentMethod.entries.map { it to resources.getString(labelId(it.wire)) }, { viewModel.filter(filter.copy(payment = it)) })
                TextButton(onClick = { viewModel.filter(BillFilter(type = filter.type)); filters = false }) { Text(stringResource(R.string.clear_filter)) }
            } } }
            if (state.loading) item { CircularProgressIndicator() }
            if (state.readFailed) item { TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) } }
            else if (!state.loading && rows.isEmpty()) item { Column(Modifier.padding(vertical = 24.dp)) {
                Text(stringResource(if (state.bills.isEmpty()) R.string.empty_bills else R.string.no_matches), style = MaterialTheme.typography.titleMedium)
                if (state.bills.isEmpty()) Text(stringResource(R.string.empty_bills_info))
            } }
            items(rows, key = { it.id }) { bill -> Card(Modifier.fillMaxWidth().clickable { viewModel.show(bill.id) }.testTag("bill_${bill.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(bill.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text((if (bill.type == BillType.INCOME) "+" else "−") + money(bill.amounts.final, locale), style = MaterialTheme.typography.titleLarge)
                    val payment = if (bill.type == BillType.STORED_VALUE_USE) state.accounts.firstOrNull { it.id == bill.accountId }?.name
                        ?: stringResource(R.string.stored_value_use) else stringResource(labelId(bill.payment.wire))
                    Text("${categoryLabel(resources, bill)} · $payment", style = MaterialTheme.typography.bodyMedium)
                    Text(dateTime(bill.occurredAt, state.zone), style = MaterialTheme.typography.bodySmall)
                    if (bill.voided(state.now) || bill.settlement == Settlement.REDEEMED) Text(stringResource(if (bill.voided(state.now)) R.string.refunded else R.string.redeemed), color = MaterialTheme.colorScheme.secondary)
                }
            } }
        }
    }
}

@Composable private fun BillTotals(state: BillsState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = remember(state.bills, state.now, state.zone) { BillSummary.calculate(state.bills, state.now, state.zone) }
    val locale = LocalConfiguration.current.locales[0]
    val minimumWidth = 148.dp * LocalDensity.current.fontScale
    val values = listOf(R.string.today_expense to summary.todayExpense, R.string.today_income to summary.todayIncome,
        R.string.today_discount to summary.todayDiscount, R.string.today_recharge to summary.todayRecharge, R.string.today_use to summary.todayUse,
        R.string.month_expense to summary.monthExpense, R.string.month_income to summary.monthIncome,
        R.string.month_discount to summary.monthDiscount, R.string.month_use to summary.monthUse)
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.take(if (expanded) values.size else 2).forEach { (label, value) -> Card(Modifier.widthIn(min = minimumWidth).weight(1f)) {
                Column(Modifier.padding(12.dp)) { Text(stringResource(label), style = MaterialTheme.typography.labelLarge); Text(money(value, locale), style = MaterialTheme.typography.titleLarge) }
            } }
        }
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(if (expanded) R.string.less_summary else R.string.more_summary)) }
    }
}
