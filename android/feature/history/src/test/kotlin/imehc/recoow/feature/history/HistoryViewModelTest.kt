package imehc.recoow.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import imehc.recoow.core.domain.history.*
import imehc.recoow.core.domain.navigation.ToolRoute
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    @Test fun changingFiltersCancelsOldPagesAndPreservesTheNewSelectionAndSavedDate() = runTest(dispatcher) {
        val repository = TestHistory(); val saved = SavedStateHandle()
        val vm = HistoryViewModel(repository, saved).also { store.put("history", it) }
        advanceUntilIdle(); assertEquals("initial", vm.state.value.entries.single().title)
        vm.more(); runCurrent(); assertTrue(vm.state.value.loading)
        vm.search("new"); advanceUntilIdle()
        assertEquals("new", vm.state.value.entries.single().title)
        repository.pending.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("new"), vm.state.value.entries.map { it.title })
        vm.selecting(); vm.select(vm.state.value.entries.single()); assertEquals(1, vm.state.value.selected.size)
        vm.date(LocalDate.of(2026, 11, 1)); advanceUntilIdle()
        assertTrue(vm.state.value.selected.isEmpty()); assertEquals("2026-11-01", saved.get<String>("historyDate"))
        assertEquals(LocalDate.of(2026, 11, 1), repository.lastQuery.date)
    }
    @Test fun failedDeletionKeepsTheUnremovedSelectionAndDoesNotResubmitWhileBusy() = runTest(dispatcher) {
        val repository = TestHistory(); val vm = HistoryViewModel(repository, SavedStateHandle()).also { store.put("history", it) }
        advanceUntilIdle(); vm.selecting(); vm.select(vm.state.value.entries.single())
        vm.prepare(); advanceUntilIdle(); assertNotNull(vm.state.value.plan)
        vm.delete(); vm.delete(); runCurrent(); assertEquals(1, repository.deletes)
        repository.pending.complete(Unit); advanceUntilIdle()
        assertTrue(vm.state.value.selecting); assertEquals(1, vm.state.value.selected.size)
        assertEquals(1, vm.state.value.deleteResult!!.failed.size); assertFalse(vm.state.value.busy)
    }
    private class TestHistory : HistoryRepository {
        val pending = CompletableDeferred<Unit>(); var deletes = 0; var lastQuery = HistoryQuery()
        override val changes = emptyFlow<Unit>()
        override suspend fun page(query: HistoryQuery, cursor: HistoryCursor?, limit: Int): HistoryPage {
            lastQuery = query
            if (cursor != null) pending.await()
            val title = query.search.ifEmpty { "initial" }
            return HistoryPage(listOf(HistoryEntry(title, ToolRoute.DIARY, title, title, 100)), if (cursor == null) HistoryCursor(100, title) else null)
        }
        override suspend fun counts(weekStart: LocalDate, zone: ZoneId) = emptyMap<LocalDate, Int>()
        override suspend fun hasEntries() = true
        override suspend fun prepareDeletion(entries: List<HistoryEntry>, zone: ZoneId) = HistoryDeletionPlan(1, zone, entries.map { HistoryDeletion(it, "revision") })
        override suspend fun delete(plan: HistoryDeletionPlan): HistoryDeletionResult {
            deletes++; pending.await(); return HistoryDeletionResult(emptySet(), plan.targets.map { it.entry.key }.toSet())
        }
    }
}
