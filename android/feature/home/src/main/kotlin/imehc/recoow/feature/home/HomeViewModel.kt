package imehc.recoow.feature.home

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.anniversaries.*
import imehc.recoow.core.domain.calendar.AnniversaryCalendar
import imehc.recoow.core.domain.reminders.*
import java.time.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HomeSummary(val date: LocalDate = LocalDate.now(), val pending: List<Reminder> = emptyList(),
    val anniversaryDays: Long? = null, val anniversaryCount: Int = 0, val remindersReady: Boolean = false,
    val anniversariesReady: Boolean = false, val failed: Boolean = false)
private data class HomeSources(val reminders: List<Reminder> = emptyList(), val anniversaries: List<Anniversary> = emptyList(),
    val ready: Set<String> = emptySet(), val failed: Set<String> = emptySet())
@HiltViewModel
class HomeViewModel @Inject constructor(private val reminders: RemindersRepository, private val anniversaries: AnniversariesRepository,
    private val calendar: AnniversaryCalendar) : ViewModel() {
    private val sources = MutableStateFlow(HomeSources())
    private val clock = MutableStateFlow(System.currentTimeMillis() to ZoneId.systemDefault())
    private val jobs = mutableMapOf<String, Job>()
    val state = combine(sources, clock) { data, clock ->
        val (now, zone) = clock; val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val nearby = data.anniversaries.mapNotNull { it.daysUntil(now, zone, calendar) }.filter { it in 0..7 }.sorted()
        HomeSummary(day, data.reminders.filter { it.canCheckIn(now, zone) }.sortedWith(compareBy<Reminder> { it.occurrence(day, zone) }.thenBy { it.id }),
            nearby.firstOrNull(), nearby.count { it == nearby.firstOrNull() }, "reminders" in data.ready, "anniversaries" in data.ready, data.failed.isNotEmpty())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeSummary())
    init { retry() }
    fun refresh(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) { clock.value = now to zone }
    fun retry() {
        observe("reminders", reminders.reminders) { data, rows -> data.copy(reminders = rows) }
        observe("anniversaries", anniversaries.records) { data, rows -> data.copy(anniversaries = rows) }
    }
    private fun <T> observe(key: String, flow: Flow<T>, update: (HomeSources, T) -> HomeSources) {
        if (jobs[key]?.isActive == true) return
        jobs[key] = viewModelScope.launch {
            try { flow.collect { rows -> sources.update { update(it, rows).copy(ready = it.ready + key, failed = it.failed - key) } } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { sources.update { it.copy(failed = it.failed + key) } }
        }
    }
}
