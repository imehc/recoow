package imehc.recoow.feature.food

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.food.*
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.domain.preferences.*
import imehc.recoow.core.domain.records.StaleRecordException
import imehc.recoow.core.media.ImageProcessor
import java.time.*
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class FoodState(val data: FoodSnapshot = FoodSnapshot(), val bills: List<BillRecord> = emptyList(), val preferences: AppPreferences = AppPreferences(),
    val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false, val error: Int? = null)
data class FoodTitleDraft(val dayStartAt: Long, val title: String, val revision: String?) : java.io.Serializable
@HiltViewModel
class FoodViewModel @Inject constructor(private val repository: FoodRepository, private val bills: BillsRepository,
    val media: MediaRepository, val processor: ImageProcessor, private val preferences: PreferencesRepository,
    private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(FoodState()); val state = mutable.asStateFlow()
    val draft = saved.getStateFlow<FoodDraft?>("foodDraft", null)
    val detail = saved.getStateFlow<String?>("foodDetail", null)
    val day = saved.getStateFlow<String?>("foodDay", null)
    val titleDraft = saved.getStateFlow<FoodTitleDraft?>("foodTitle", null)
    val pickingBills = saved.getStateFlow("foodPickingBills", false)
    val query = saved.getStateFlow("foodQuery", "")
    private var observation: Job? = null
    init { retry() }
    fun retry() {
        observation?.cancel(); mutable.update { it.copy(loading = true, failed = false) }
        observation = viewModelScope.launch { try {
            combine(repository.snapshot, bills.bills, preferences.preferences) { data, bills, prefs -> Triple(data, bills, prefs) }
                .collect { (data, bills, prefs) -> mutable.update { it.copy(data = data, bills = bills.filterNot { it.deleted }, preferences = prefs, loading = false) } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(loading = false, failed = true) } } }
    }
    fun query(value: String) { saved["foodQuery"] = value }
    fun showDay(value: LocalDate) { saved["foodDay"] = value.toString(); saved["foodDetail"] = null; saved["foodDraft"] = null; saved["foodTitle"] = null; saved["foodPickingBills"] = false }
    fun show(id: String) { saved["foodDetail"] = id; saved["foodDraft"] = null }
    fun edit(entry: FoodEntry? = null) {
        val today = LocalDate.now(); val target = day.value?.let(LocalDate::parse) ?: today
        val timestamp = if (target == today) System.currentTimeMillis() else target.atTime(LocalTime.now().withSecond(0).withNano(0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        saved["foodDraft"] = entry?.let(FoodDraft::from) ?: FoodDraft(occurredAt = timestamp)
    }
    fun copy(entry: FoodEntry, target: LocalDate) { saved["foodDraft"] = FoodDraft.duplicate(entry, target, ZoneId.systemDefault()) }
    fun update(value: FoodDraft) { if (!state.value.busy) saved["foodDraft"] = value }
    fun editTitle(group: FoodDay) { saved["foodTitle"] = FoodTitleDraft(group.date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), group.record?.title.orEmpty(), group.record?.revision) }
    fun updateTitle(value: FoodTitleDraft) { if (!state.value.busy) saved["foodTitle"] = value }
    fun pickBills() { saved["foodPickingBills"] = true }
    fun back() { if (!state.value.busy) when {
        pickingBills.value -> saved["foodPickingBills"] = false; titleDraft.value != null -> saved["foodTitle"] = null
        draft.value != null -> saved["foodDraft"] = null; detail.value != null -> saved["foodDetail"] = null; else -> saved["foodDay"] = null
    } }
    fun save() = action {
        val value = requireNotNull(draft.value); val id = repository.save(value)
        saved["foodDraft"] = null; saved["foodDay"] = Instant.ofEpochMilli(value.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate().toString(); saved["foodDetail"] = id
        value.attachments.forEach { it.content?.file?.delete() }
    }
    fun saveTitle() = action { val d = requireNotNull(titleDraft.value); repository.saveDayTitle(d.dayStartAt, d.title, d.revision); saved["foodTitle"] = null }
    fun duplicate(entries: List<FoodEntry>, target: LocalDate) = action { repository.duplicate(entries.associate { it.id to it.revision }, target, ZoneId.systemDefault()); showDay(target) }
    fun delete(entry: FoodEntry) = action { repository.delete(entry.id, entry.revision); saved["foodDetail"] = null }
    fun deleteDay(group: FoodDay) = action { repository.deleteDay(group.date, ZoneId.systemDefault(), group.entries.associate { it.id to it.revision }, group.record?.revision); saved["foodDay"] = null }
    fun clearError() { mutable.update { it.copy(error = null) } }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e }
            catch (_: StaleRecordException) { mutable.update { it.copy(error = CommonR.string.record_stale) } }
            catch (_: IllegalArgumentException) { mutable.update { it.copy(error = CommonR.string.record_invalid) } }
            catch (_: Exception) { mutable.update { it.copy(error = CommonR.string.record_failed) } }
            finally { mutable.update { it.copy(busy = false) } } }
    }
}
