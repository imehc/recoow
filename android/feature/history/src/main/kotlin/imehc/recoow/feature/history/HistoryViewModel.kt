package imehc.recoow.feature.history

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.history.*
import imehc.recoow.core.domain.navigation.ToolRoute
import java.time.*
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HistoryState(val entries: List<HistoryEntry> = emptyList(), val next: HistoryCursor? = null,
    val loading: Boolean = true, val failed: Boolean = false, val countsFailed: Boolean = false,
    val counts: Map<LocalDate, Int> = emptyMap(), val hasAny: Boolean = false,
    val selected: Map<String, HistoryEntry> = emptyMap(), val selecting: Boolean = false,
    val busy: Boolean = false, val plan: HistoryDeletionPlan? = null, val error: Boolean = false, val deleteResult: HistoryDeletionResult? = null)
@HiltViewModel
class HistoryViewModel @Inject constructor(private val repository: HistoryRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(HistoryState()); val state = mutable.asStateFlow()
    val search = saved.getStateFlow("historySearch", "")
    val date = saved.getStateFlow<String?>("historyDate", null)
    val route = saved.getStateFlow<ToolRoute?>("historyRoute", null)
    val week = saved.getStateFlow("historyWeek", LocalDate.now().toString())
    private val environment = MutableStateFlow(ZoneId.systemDefault() to Locale.getDefault())
    private val refresh = MutableStateFlow(0)
    private var generation = 0L
    private var pageJob: Job? = null
    private var changesJob: Job? = null
    private val query = combine(search, date, route, environment) { search, date, route, env -> HistoryQuery(date?.let(LocalDate::parse), route, search, env.first) }
    init {
        viewModelScope.launch {
            combine(query, refresh) { query, _ -> query }.collectLatest { query ->
                val request = ++generation; pageJob?.cancel()
                mutable.update { it.copy(loading = true, failed = false, entries = emptyList(), next = null) }
                try { val result = repository.page(query); currentCoroutineContext().ensureActive()
                    if (request == generation) mutable.update { it.copy(entries = result.entries, next = result.next, loading = false) }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { if (request == generation) mutable.update { it.copy(loading = false, failed = true) } }
            }
        }
        viewModelScope.launch {
            combine(week, environment, refresh) { anchor, env, _ -> Triple(LocalDate.parse(anchor), env.first, env.second) }.collectLatest { (anchor, zone, locale) ->
                try {
                    val start = anchor.with(WeekFields.of(locale).dayOfWeek(), 1)
                    val counts = repository.counts(start, zone); val any = repository.hasEntries()
                    currentCoroutineContext().ensureActive(); mutable.update { it.copy(counts = counts, hasAny = any, countsFailed = false) }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(countsFailed = true) } }
            }
        }
        observeChanges()
    }
    private fun observeChanges() {
        if (changesJob?.isActive == true) return
        changesJob = viewModelScope.launch {
            try { repository.changes.collect { refresh.update { it + 1 } } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(failed = true, countsFailed = true) } }
        }
    }
    fun environment(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()) { environment.value = zone to locale }
    fun retry() { observeChanges(); refresh.update { it + 1 } }
    private fun clearSelection() { mutable.update { it.copy(selected = emptyMap(), selecting = false, plan = null) } }
    fun search(value: String) { if (!state.value.busy) { clearSelection(); saved["historySearch"] = value } }
    fun route(value: ToolRoute?) { if (!state.value.busy) { clearSelection(); saved["historyRoute"] = value } }
    fun date(value: LocalDate?) { if (!state.value.busy) { clearSelection(); saved["historyDate"] = value?.toString(); value?.let { saved["historyWeek"] = it.toString() } } }
    fun week(offset: Long) { saved["historyWeek"] = LocalDate.parse(week.value).plusWeeks(offset).toString() }
    fun selecting() { mutable.update { it.copy(selecting = !it.selecting, selected = emptyMap()) } }
    fun select(entry: HistoryEntry) { mutable.update { it.copy(selected = if (entry.key in it.selected) it.selected - entry.key else it.selected + (entry.key to entry)) } }
    fun selectLoaded() { mutable.update { it.copy(selected = it.selected + it.entries.associateBy { row -> row.key }) } }
    fun more() {
        if (state.value.loading || state.value.busy || pageJob?.isActive == true) return
        val cursor = state.value.next ?: return; val request = generation
        pageJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, failed = false) }
            try { val q = HistoryQuery(date.value?.let(LocalDate::parse), route.value, search.value, environment.value.first)
                val page = repository.page(q, cursor); currentCoroutineContext().ensureActive()
                if (request == generation) mutable.update { it.copy(entries = (it.entries + page.entries).distinctBy(HistoryEntry::key), next = page.next, loading = false) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { if (request == generation) mutable.update { it.copy(loading = false, failed = true) } }
        }
    }
    fun prepare(entries: List<HistoryEntry> = state.value.selected.values.toList()) = action {
        val plan = repository.prepareDeletion(entries, environment.value.first); mutable.update { it.copy(plan = plan) }
    }
    fun dismiss() { if (!state.value.busy) mutable.update { it.copy(plan = null, error = false, deleteResult = null) } }
    fun delete() = action {
        val plan = state.value.plan ?: return@action
        val result = repository.delete(plan)
        mutable.update { it.copy(plan = null, deleteResult = result, selected = it.selected - result.deleted, selecting = result.failed.isNotEmpty()) }
        retry()
    }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = false) }
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(plan = null, error = true) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
