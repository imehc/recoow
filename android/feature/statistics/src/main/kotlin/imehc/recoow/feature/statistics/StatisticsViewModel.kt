package imehc.recoow.feature.statistics

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.domain.statistics.*
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class StatisticsSources(val dates: Map<ToolRoute, List<Long>> = emptyMap(), val bills: List<BillRecord> = emptyList(),
    val reminders: List<Reminder> = emptyList(), val loading: Set<String> = emptySet(), val failed: Set<String> = emptySet())
data class StatisticsClock(val now: Long = System.currentTimeMillis(), val zone: ZoneId = ZoneId.systemDefault(), val locale: Locale = Locale.getDefault())
data class StatisticsState(val overview: UsageOverview = UsageOverview(), val progress: List<CheckInProgress> = emptyList(),
    val bills: BillStatistics? = null, val trend: List<BillTrend> = emptyList(), val expenseShares: List<BillShare> = emptyList(),
    val incomeShares: List<BillShare> = emptyList(), val loading: Set<String> = emptySet(), val failed: Set<String> = emptySet(),
    val totals: Map<BillMetric, java.math.BigInteger> = emptyMap())

@HiltViewModel
class StatisticsViewModel @Inject constructor(private val repository: StatisticsRepository, private val bills: BillsRepository,
    private val reminders: RemindersRepository, private val saved: SavedStateHandle, val media: MediaRepository? = null) : ViewModel() {
    private val sources = MutableStateFlow(StatisticsSources())
    private val clock = MutableStateFlow(StatisticsClock())
    val period = saved.getStateFlow("statisticsPeriod", BillPeriod.MONTH)
    private val jobs = mutableMapOf<String, Job>()
    val state = combine(sources, clock, period) { data, clock, period ->
        val financial = BillStatistics(data.bills, period, clock.now, clock.zone, clock.locale)
        StatisticsState(usageOverview(data.dates, clock.now, clock.zone), checkInProgress(data.reminders, clock.now, clock.zone),
            financial, financial.trend(), financial.shares(BillType.EXPENSE), financial.shares(BillType.INCOME), data.loading, data.failed,
            BillMetric.entries.associateWith(financial::total))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsState())
    init { retry() }
    fun period(value: BillPeriod) { saved["statisticsPeriod"] = value }
    fun refresh(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) {
        clock.value = StatisticsClock(now, zone, locale)
    }
    fun retry() {
        ToolRoute.entries.forEach { route -> observe(route.id, repository.dates(route)) { data, value -> data.copy(dates = data.dates + (route to value)) } }
        observe("billDetails", bills.bills) { data, value -> data.copy(bills = value) }
        observe("checkInProgress", reminders.reminders) { data, value -> data.copy(reminders = value) }
    }
    private fun <T> observe(key: String, flow: Flow<T>, update: (StatisticsSources, T) -> StatisticsSources) {
        if (jobs[key]?.isActive == true) return
        sources.update { it.copy(loading = it.loading + key, failed = it.failed - key) }
        jobs[key] = viewModelScope.launch {
            try { flow.collect { value -> sources.update { update(it, value).copy(loading = it.loading - key, failed = it.failed - key) } } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { sources.update { it.copy(loading = it.loading - key, failed = it.failed + key) } }
        }
    }
}
