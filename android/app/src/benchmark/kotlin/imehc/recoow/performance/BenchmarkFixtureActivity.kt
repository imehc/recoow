package imehc.recoow.performance

import android.os.Bundle
import android.app.Application
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.anniversaries.*
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.decision.*
import imehc.recoow.core.domain.diary.*
import imehc.recoow.core.domain.food.*
import imehc.recoow.core.domain.items.*
import imehc.recoow.core.domain.preferences.*
import imehc.recoow.core.domain.records.*
import imehc.recoow.core.domain.reminders.*
import imehc.recoow.core.domain.tracks.TracksRepository
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Only packaged in the isolated baseline/benchmark application, never debug or release. */
@AndroidEntryPoint
class BenchmarkFixtureActivity : ComponentActivity() {
    private val fixture: BenchmarkFixtureViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "imehc.recoow.benchmark")
        val status = TextView(this)
        setContentView(status)
        lifecycleScope.launch { fixture.status.collect { status.text = it } }
    }
}

@HiltViewModel
class BenchmarkFixtureViewModel @Inject constructor(
    application: Application,
    private val bills: BillsRepository,
    private val diary: DiaryRepository,
    private val food: FoodRepository,
    private val items: ItemsRepository,
    private val reminders: RemindersRepository,
    private val anniversaries: AnniversariesRepository,
    private val decisions: DecisionsRepository,
    private val tracks: TracksRepository,
    private val records: RecordRepository,
    private val preferences: PreferencesRepository,
) : AndroidViewModel(application) {
    private val mutableStatus = MutableStateFlow("Preparing benchmark data")
    val status = mutableStatus.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { seed() }
                mutableStatus.value = "Benchmark fixture ready"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableStatus.value = "Benchmark fixture failed: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
    }

    private suspend fun seed() {
        val marker = getApplication<Application>().getSharedPreferences("benchmark_fixture", Application.MODE_PRIVATE)
        if (marker.getBoolean("ready_v1", false)) return
        // A failed/interrupted seed must be cleared by the harness, never silently reused.
        check(records.find("bills", "perf-bill-0") == null) { "Clear benchmark application data before retrying" }
        preferences.setLanguage(AppLanguage.ENGLISH)
        preferences.setAppearance(Appearance.LIGHT)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        fun time(day: Int) = today.minusDays(day.toLong()).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        repeat(120) { index ->
            bills.save(BillDraft(id = "perf-bill-$index", title = "Performance bill $index", occurredAt = time(index % 30) + index,
                amounts = AmountInput(original = "20.00", final = "18.50")))
        }
        repeat(40) { index ->
            diary.save(DiaryDraft(id = "perf-diary-$index", title = "Performance diary $index", occurredAt = time(index),
                content = "A populated journal entry for scrolling, detail and navigation measurement.\n".repeat(8)), "Diary")
            food.save(FoodDraft(id = "perf-food-$index", title = "Performance meal $index", occurredAt = time(index), meal = MealKind.LUNCH))
            items.save(ItemDraft(id = "perf-item-$index", title = "Performance item $index", location = "Shelf ${index % 5}", note = "A searchable stored item"))
        }
        food.saveDayTitle(today.atStartOfDay(zone).toInstant().toEpochMilli(), "Performance food day", null)
        repeat(12) { index ->
            reminders.save(ReminderDraft(id = "perf-reminder-$index", title = "Performance reminder $index", scheduledAt = time(0), enabled = false))
            anniversaries.save(AnniversaryDraft(id = "perf-anniversary-$index", title = "Performance anniversary $index", occurredAt = time(index), enabled = false))
        }
        decisions.saveCollection(CollectionDraft(id = "perf-collection", title = "Performance collection"))
        listOf("apple", "banana", "orange", "coffee", "pizza", "hamburger", "watermelon", "cake", "pear", "tea").forEachIndexed { index, title ->
            decisions.saveOption(OptionDraft(id = "perf-option-$index", collectionId = "perf-collection", title = title))
        }
        val start = time(1)
        fun change(table: String, id: String, fields: JSONObject) = RecordChange(table, id, fields.toString(), start, "benchmark-fixture")
        records.save(buildList {
            add(change("tracks", "perf-track", JSONObject().put("name", "Performance track").put("status", "finished")
                .put("started_at", start).put("ended_at", start + 599_000).put("duration_s", 599)
                .put("distance_m", 0).put("desired_accuracy_m", 10)))
            repeat(600) { index ->
                add(change("track_points", "perf-point-$index", JSONObject().put("track_id", "perf-track")
                    .put("timestamp_ms", start + index * 1000L).put("latitude", 31.23 + index * 0.00001)
                    .put("longitude", 121.47 + index * 0.000005).put("horizontal_acc", 5).put("speed_mps", 1.2)))
            }
        })
        tracks.ensureSegments("perf-track")
        check(marker.edit().putBoolean("ready_v1", true).commit())
    }
}
