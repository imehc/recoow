package imehc.recoow.feature.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.RecoowPage
import androidx.lifecycle.repeatOnLifecycle
import imehc.recoow.core.domain.bills.*
import java.math.BigInteger
import java.time.*
import java.time.temporal.WeekFields
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(bills: List<BillRecord> = emptyList(), loading: Boolean = false, failed: Boolean = false, onRetry: () -> Unit = {}, onBill: (String) -> Unit = {}) {
    var period by rememberSaveable { mutableIntStateOf(1) }
    var metric by rememberSaveable { mutableStateOf<Int?>(null) }
    var amountSort by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) } } }
    val statistics = BillStatistics(bills, BillPeriod.entries[period], now, zone, locale)
    val labels = listOf(R.string.statistics_expense, R.string.statistics_income, R.string.statistics_bill_count, R.string.statistics_discount, R.string.statistics_stored_use)
    fun subset(index: Int) = statistics.rows(BillMetric.entries[index])
    fun money(cents: BigInteger) = NumberFormat.getCurrencyInstance(locale).apply { currency = java.util.Currency.getInstance("CNY") }.format(cents.toBigDecimal(2))
    RecoowPage(stringResource(R.string.statistics_title)) {
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(R.string.statistics_week, R.string.statistics_month, R.string.statistics_year).forEachIndexed { i, label -> FilterChip(period == i, { period = i }, label = { Text(stringResource(label)) }) } } }
        item { Text("${statistics.start} — ${statistics.endExclusive.minusDays(1)}", style = MaterialTheme.typography.bodySmall) }
        if (loading) item { CircularProgressIndicator() }
        else if (failed) item { TextButton(onClick = onRetry) { Text(stringResource(R.string.statistics_retry)) } }
        else labels.forEachIndexed { index, label -> item {
            Card(onClick = { metric = index }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
                val total = statistics.total(BillMetric.entries[index])
                Text(if (index == 2) total.toString() else money(total), style = MaterialTheme.typography.headlineSmall)
            } }
        } }
    }
    metric?.let { index -> ModalBottomSheet(onDismissRequest = { metric = null }) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(labels[index]), style = MaterialTheme.typography.titleLarge) }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!amountSort, { amountSort = false }, label = { Text(stringResource(R.string.statistics_sort_date)) })
                FilterChip(amountSort, { amountSort = true }, label = { Text(stringResource(R.string.statistics_sort_amount)) })
            } }
            val details = subset(index).let { list -> if (amountSort) list.sortedByDescending { if (index == 3) it.amounts.discount else it.amounts.final } else list.sortedByDescending { it.occurredAt } }
            if (details.isEmpty()) item { Text(stringResource(R.string.statistics_no_bills)) }
            details.forEach { bill -> item(key = bill.id) { OutlinedCard(onClick = { metric = null; onBill(bill.id) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(bill.title, style = MaterialTheme.typography.titleMedium)
                Text(money(BigInteger.valueOf(if (index == 3) bill.amounts.discount else bill.amounts.final)))
                Text(Instant.ofEpochMilli(bill.occurredAt).atZone(zone).toLocalDate().toString(), style = MaterialTheme.typography.bodySmall)
            } } } }
        }
    } }
}
