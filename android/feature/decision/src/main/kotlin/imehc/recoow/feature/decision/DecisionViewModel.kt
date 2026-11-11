package imehc.recoow.feature.decision

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.decision.*
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.domain.preferences.*
import imehc.recoow.core.domain.records.StaleRecordException
import imehc.recoow.core.media.ImageProcessor
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DecisionState(val data: DecisionSnapshot = DecisionSnapshot(), val preferences: AppPreferences = AppPreferences(),
    val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false, val error: Int? = null)
@HiltViewModel
class DecisionViewModel @Inject constructor(private val repository: DecisionsRepository, val media: MediaRepository,
    val processor: ImageProcessor, private val preferences: PreferencesRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(DecisionState()); val state = mutable.asStateFlow()
    val collection = saved.getStateFlow<String?>("decisionCollection", null)
    val collectionDraft = saved.getStateFlow<CollectionDraft?>("decisionCollectionDraft", null)
    val optionDraft = saved.getStateFlow<OptionDraft?>("decisionOptionDraft", null)
    val history = saved.getStateFlow("decisionHistory", false)
    val detail = saved.getStateFlow<String?>("decisionDetail", null)
    val query = saved.getStateFlow("decisionCollectionQuery", "")
    val historyQuery = saved.getStateFlow("decisionHistoryQuery", "")
    fun query(value: String) { saved["decisionCollectionQuery"] = value }
    fun queryHistory(value: String) { saved["decisionHistoryQuery"] = value }
    val shownResult = saved.getStateFlow<String?>("decisionShownResult", null)
    fun markResultShown(id: String) { saved["decisionShownResult"] = id }
    val result = saved.getStateFlow<String?>("decisionResult", null)
    private var observation: Job? = null
    init { retry() }
    fun retry() { observation?.cancel(); mutable.update { it.copy(loading = true, failed = false) }
        observation = viewModelScope.launch { try { combine(repository.snapshot, preferences.preferences) { data, prefs -> data to prefs }.collect { (data, prefs) ->
            mutable.update { it.copy(data = data, preferences = prefs, loading = false) }
        } } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(loading = false, failed = true) } } }
    }
    fun open(id: String) { saved["decisionCollection"] = id; saved["decisionResult"] = null; saved["decisionHistory"] = false }
    fun show(id: String) { saved["decisionDetail"] = id; saved["decisionOptionDraft"] = null; saved["decisionCollectionDraft"] = null }
    fun editCollection(value: DecisionCollection? = null) { saved["decisionCollectionDraft"] = value?.let(CollectionDraft::from) ?: CollectionDraft() }
    fun editOption(value: DecisionOption? = null) { saved["decisionOptionDraft"] = value?.let(OptionDraft::from) ?: OptionDraft(collectionId = requireNotNull(collection.value)) }
    fun update(value: CollectionDraft) { if (!state.value.busy) saved["decisionCollectionDraft"] = value }
    fun update(value: OptionDraft) { if (!state.value.busy) saved["decisionOptionDraft"] = value }
    fun showHistory() { saved["decisionHistory"] = true }
    fun back() { if (!state.value.busy) when {
        optionDraft.value != null -> saved["decisionOptionDraft"] = null
        collectionDraft.value != null -> saved["decisionCollectionDraft"] = null
        detail.value != null -> saved["decisionDetail"] = null
        history.value -> saved["decisionHistory"] = false
        else -> { saved["decisionCollection"] = null; saved["decisionResult"] = null }
    } }
    fun saveCollection() = action { val id = repository.saveCollection(requireNotNull(collectionDraft.value)); saved["decisionCollectionDraft"] = null; open(id) }
    fun saveOption() = action { val d = requireNotNull(optionDraft.value); repository.saveOption(d); saved["decisionOptionDraft"] = null; d.imageContent?.file?.delete() }
    fun deleteCollection(c: DecisionCollection) = action { repository.deleteCollection(c.id, c.revision); if (collection.value == c.id) saved["decisionCollection"] = null }
    fun deleteOption(o: DecisionOption) = action {
        repository.deleteOption(o.id, o.revision)
        if (optionDraft.value?.id == o.id) saved["decisionOptionDraft"] = null
    }
    fun deleteChoice(c: DecisionChoice) = action { repository.deleteChoice(c.id, c.revision); saved["decisionDetail"] = null; if (result.value == c.id) saved["decisionResult"] = null }
    fun choose() = action { saved["decisionResult"] = repository.choose(requireNotNull(collection.value)) }
    fun clearError() { mutable.update { it.copy(error = null) } }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e }
            catch (_: StaleRecordException) { mutable.update { it.copy(error = CommonR.string.record_stale) } }
            catch (_: IllegalArgumentException) { mutable.update { it.copy(error = R.string.decision_unavailable) } }
            catch (_: Exception) { mutable.update { it.copy(error = CommonR.string.record_failed) } }
            finally { mutable.update { it.copy(busy = false) } } }
    }
}
