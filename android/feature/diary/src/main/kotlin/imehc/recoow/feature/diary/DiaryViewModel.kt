package imehc.recoow.feature.diary

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.diary.*
import imehc.recoow.core.domain.location.*
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.domain.preferences.*
import imehc.recoow.core.domain.records.*
import imehc.recoow.core.media.ImageProcessor
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DiaryState(val data: DiarySnapshot = DiarySnapshot(), val preferences: AppPreferences = AppPreferences(),
    val loading: Boolean = true, val failed: Boolean = false, val busy: Boolean = false, val error: Int? = null, val locating: Boolean = false)
@HiltViewModel
class DiaryViewModel @Inject constructor(private val repository: DiaryRepository, val media: MediaRepository,
    val processor: ImageProcessor, private val preferences: PreferencesRepository, private val saved: SavedStateHandle,
    private val location: LocationSource) : ViewModel() {
    private val mutable = MutableStateFlow(DiaryState()); val state = mutable.asStateFlow()
    val draft = saved.getStateFlow<DiaryDraft?>("diaryDraft", null)
    val detail = saved.getStateFlow<String?>("diaryDetail", null)
    val managing = saved.getStateFlow("diaryTags", false)
    val tagDraft = saved.getStateFlow<NamedRecordDraft?>("diaryTagDraft", null)
    val linkSource = saved.getStateFlow<DiarySource?>("diaryLinkSource", null)
    val query = saved.getStateFlow("diaryQuery", "")
    val tagFilter = saved.getStateFlow<String?>("diaryTagFilter", null)
    val dateFilter = saved.getStateFlow<String?>("diaryDateFilter", null)
    private val sourcesMutable = MutableStateFlow<List<DiaryLink>>(emptyList()); val sources = sourcesMutable.asStateFlow()
    private val sourceLoadingMutable = MutableStateFlow(false); val sourceLoading = sourceLoadingMutable.asStateFlow()
    private val sourceFailedMutable = MutableStateFlow(false); val sourceFailed = sourceFailedMutable.asStateFlow()
    private var observation: Job? = null; private var sourceObservation: Job? = null
    private var locationJob: Job? = null
    init { retry(); linkSource.value?.let(::selectSource) }
    fun retry() {
        observation?.cancel(); mutable.update { it.copy(loading = true, failed = false) }
        observation = viewModelScope.launch { try {
            combine(repository.snapshot, preferences.preferences) { data, prefs -> data to prefs }.collect { (data, prefs) -> mutable.update { it.copy(data = data, preferences = prefs, loading = false) } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(loading = false, failed = true) } } }
    }
    fun query(value: String) { saved["diaryQuery"] = value }
    fun filterTag(value: String?) { saved["diaryTagFilter"] = value }
    fun filterDate(value: String?) { saved["diaryDateFilter"] = value }
    fun show(id: String) { saved["diaryDetail"] = id; saved["diaryDraft"] = null; saved["diaryTags"] = false; closeSources() }
    fun edit(entry: DiaryEntry? = null) { saved["diaryDraft"] = entry?.let(DiaryDraft::from) ?: DiaryDraft() }
    fun update(value: DiaryDraft) { if (!state.value.busy) saved["diaryDraft"] = value }
    fun locationDenied() { mutable.update { it.copy(error = R.string.diary_location_denied) } }
    fun cancelLocation() { locationJob?.cancel(); locationJob = null; mutable.update { it.copy(locating = false) } }
    fun locate() {
        if (state.value.busy || state.value.locating || draft.value == null) return
        val id = draft.value!!.id
        mutable.update { it.copy(locating = true) }
        locationJob = viewModelScope.launch {
            try {
                val observation = withTimeoutOrNull(20_000) { location.observe(1000, 0f).first() }
                when (observation) {
                    is LocationObservation.Fix -> draft.value?.takeIf { it.id == id }?.let { d ->
                        val fix = observation.location
                        update(d.copy(latitude = fix.coordinate.latitude, longitude = fix.coordinate.longitude, accuracy = fix.accuracyMeters.toDouble().takeIf { it.isFinite() && it >= 0 }))
                    }
                    LocationObservation.PermissionRequired -> locationDenied()
                    else -> mutable.update { it.copy(error = R.string.diary_location_unavailable) }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(error = R.string.diary_location_unavailable) } }
            finally { mutable.update { it.copy(locating = false) } }
        }
    }
    fun manageTags() { saved["diaryTags"] = true }
    fun editTag(value: NamedRecordDraft) { if (!state.value.busy) saved["diaryTagDraft"] = value }
    fun selectSource(source: DiarySource) {
        saved["diaryLinkSource"] = source; sourceObservation?.cancel(); sourcesMutable.value = emptyList(); sourceLoadingMutable.value = true; sourceFailedMutable.value = false
        sourceObservation = viewModelScope.launch { try { repository.sources(source).collect { sourcesMutable.value = it; sourceLoadingMutable.value = false } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { sourceLoadingMutable.value = false; sourceFailedMutable.value = true } }
    }
    private fun closeSources() { saved["diaryLinkSource"] = null; sourceObservation?.cancel() }
    fun back() { if (!state.value.busy) when {
        linkSource.value != null -> closeSources(); tagDraft.value != null -> saved["diaryTagDraft"] = null
        managing.value -> saved["diaryTags"] = false; draft.value != null -> saved["diaryDraft"] = null; else -> saved["diaryDetail"] = null
    } }
    fun save(defaultTitle: String) = action {
        val value = requireNotNull(draft.value); val id = repository.save(value, defaultTitle)
        saved["diaryDraft"] = null; saved["diaryDetail"] = id
        value.attachments.forEach { it.content?.file?.delete() }
    }
    fun saveTag() = action { repository.saveTag(requireNotNull(tagDraft.value)); saved["diaryTagDraft"] = null }
    fun deleteTag(tag: NamedRecord) = action { repository.deleteTag(tag.id, tag.revision) }
    fun delete(entry: DiaryEntry) = action { repository.delete(entry.id, entry.revision); saved["diaryDetail"] = null }
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
