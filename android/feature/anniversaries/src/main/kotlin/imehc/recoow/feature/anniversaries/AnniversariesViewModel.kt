package imehc.recoow.feature.anniversaries

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.domain.anniversaries.*
import imehc.recoow.core.domain.calendar.AnniversaryCalendar
import imehc.recoow.core.domain.records.StaleRecordException
import imehc.recoow.core.designsystem.R as CommonR
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AnniversaryState(val records: List<Anniversary> = emptyList(), val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false, val error: Int? = null)
@HiltViewModel
class AnniversariesViewModel @Inject constructor(private val repository: AnniversariesRepository, val calendar: AnniversaryCalendar,
    private val notifications: AnniversaryNotifications, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(AnniversaryState()); val state = mutable.asStateFlow()
    val notificationState = notifications.state
    val draft = saved.getStateFlow<AnniversaryDraft?>("anniversaryDraft", null)
    val detail = saved.getStateFlow<String?>("anniversaryDetail", null)
    val query = saved.getStateFlow("anniversaryQuery", "")
    private var observation: Job? = null
    init { retry() }
    fun retry() { observation?.cancel(); mutable.update { it.copy(loading = true, failed = false) }
        observation = viewModelScope.launch { try { repository.records.collect { records -> mutable.update { it.copy(records = records, loading = false) } } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(loading = false, failed = true) } } }
    }
    fun refreshNotifications() { viewModelScope.launch { try { notifications.refreshAccess(); notifications.requestReconcile() } catch (_: Exception) { } } }
    fun query(value: String) { saved["anniversaryQuery"] = value }
    fun show(id: String) { saved["anniversaryDetail"] = id; saved["anniversaryDraft"] = null }
    fun edit(record: Anniversary? = null) { saved["anniversaryDraft"] = record?.let(AnniversaryDraft::from) ?: AnniversaryDraft() }
    fun update(value: AnniversaryDraft) { if (!state.value.busy) saved["anniversaryDraft"] = value }
    fun back() { if (!state.value.busy) { if (draft.value != null) saved["anniversaryDraft"] = null else saved["anniversaryDetail"] = null } }
    fun save() = action { val id = repository.save(requireNotNull(draft.value)); saved["anniversaryDraft"] = null; saved["anniversaryDetail"] = id }
    fun delete(record: Anniversary) = action { repository.delete(record.id, record.revision); saved["anniversaryDetail"] = null }
    fun clearError() { mutable.update { it.copy(error = null) } }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { try { block(); runCatching { notifications.requestReconcile() } }
            catch (e: CancellationException) { throw e }
            catch (_: StaleRecordException) { mutable.update { it.copy(error = CommonR.string.record_stale) } }
            catch (_: Exception) { mutable.update { it.copy(error = CommonR.string.record_failed) } }
            finally { mutable.update { it.copy(busy = false) } } }
    }
}
