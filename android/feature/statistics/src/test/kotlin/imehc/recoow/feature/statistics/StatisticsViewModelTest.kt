package imehc.recoow.feature.statistics

import androidx.lifecycle.*
import imehc.recoow.core.domain.bills.BillsRepository
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.domain.reminders.RemindersRepository
import imehc.recoow.core.domain.statistics.StatisticsRepository
import java.lang.reflect.Proxy
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    @Test fun oneSourceFailureKeepsOtherTotalsAndRetryRestoresOnlyTheFailedSource() = runTest(dispatcher) {
        val dates = MutableStateFlow(listOf(1_000L)); var fail = true
        val repository = object : StatisticsRepository {
            override fun dates(route: ToolRoute) = flow {
                if (route == ToolRoute.ITEMS && fail) throw IllegalStateException("Unreadable source")
                emitAll(dates)
            }
        }
        val vm = StatisticsViewModel(repository, proxy(BillsRepository::class.java), proxy(RemindersRepository::class.java), SavedStateHandle()).also { store.put("statistics", it) }
        vm.refresh(2_000L, ZoneOffset.UTC, Locale.US)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        val failed = withContext(Dispatchers.Default) { withTimeout(5_000) { vm.state.first { ToolRoute.ITEMS.id in it.failed && it.loading.isEmpty() } } }
        assertEquals(7, failed.overview.total); assertEquals(7, failed.overview.today)
        fail = false; vm.retry()
        val restored = withContext(Dispatchers.Default) { withTimeout(5_000) { vm.state.first { it.failed.isEmpty() && it.overview.total == 8 } } }
        assertEquals(8, restored.overview.total)
        vm.refresh(86_402_000L, ZoneOffset.UTC, Locale.US)
        val tomorrow = withContext(Dispatchers.Default) { withTimeout(5_000) { vm.state.first { it.overview.today == 0 && it.overview.total == 8 } } }
        assertEquals(1, tomorrow.overview.activeDays); collector.cancel()
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        if (method.name == "getBills" || method.name == "getReminders") flowOf(emptyList<Any>()) else error("Unexpected ${method.name}")
    } as T
}
