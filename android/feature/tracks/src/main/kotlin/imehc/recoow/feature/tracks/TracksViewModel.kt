package imehc.recoow.feature.tracks

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.records.StaleRecordException
import imehc.recoow.core.domain.tracks.*
import imehc.recoow.core.location.TrackRecordingRuntime
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TracksState(val tracks: List<Track> = emptyList(), val detail: TrackDetail = TrackDetail(),
    val loading: Boolean = true, val detailLoading: Boolean = false, val failed: Boolean = false, val detailFailed: Boolean = false,
    val busy: Boolean = false, val error: Int? = null)
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TracksViewModel @Inject constructor(private val repository: TracksRepository, val recorder: TrackRecordingRuntime,
    private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(TracksState()); val state = mutable.asStateFlow()
    val detailId = saved.getStateFlow<String?>("trackDetail", null)
    val history = saved.getStateFlow("trackHistory", false)
    val draft = saved.getStateFlow<TrackDraft?>("trackDraft", null)
    val accuracy = saved.getStateFlow("trackAccuracy", 10)
    val activeDetail = recorder.state.map { it.current?.id }.distinctUntilChanged().flatMapLatest { id ->
        if (id == null) flowOf(TrackDetail()) else repository.detail(id).catch { emit(TrackDetail()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrackDetail())
    private var listing: Job? = null; private var detailJob: Job? = null
    init { recorder.initialize(); retry(); observeDetail() }
    fun retry() { listing?.cancel(); mutable.update { it.copy(loading = true, failed = false) }
        listing = viewModelScope.launch { try { repository.tracks.collect { rows -> mutable.update { it.copy(tracks = rows, loading = false) } } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(loading = false, failed = true) } } }
    }
    fun observeDetail() { detailJob?.cancel(); detailJob = viewModelScope.launch {
        detailId.collectLatest { id ->
            mutable.update { it.copy(detail = TrackDetail(), detailLoading = id != null, detailFailed = false) }
            if (id != null) try { repository.ensureSegments(id); repository.detail(id).collect { d -> mutable.update { it.copy(detail = d, detailLoading = false) } } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(detailLoading = false, detailFailed = true) } }
        }
    } }
    fun show(id: String) { saved["trackDetail"] = id; saved["trackDraft"] = null }
    fun showHistory() { saved["trackHistory"] = true }
    fun showRecording() { saved["trackDetail"] = null; saved["trackDraft"] = null; saved["trackHistory"] = false }
    fun accuracy(value: Int) { if (recorder.state.value.current == null) saved["trackAccuracy"] = value }
    fun edit(track: Track) { saved["trackDraft"] = TrackDraft(track.id, track.revision, track.name, track.note.orEmpty()) }
    fun update(value: TrackDraft) { if (!state.value.busy) saved["trackDraft"] = value }
    fun back() { if (!state.value.busy) when { draft.value != null -> saved["trackDraft"] = null; detailId.value != null -> saved["trackDetail"] = null; else -> saved["trackHistory"] = false } }
    fun save() = action { repository.edit(requireNotNull(draft.value)); saved["trackDraft"] = null; recorder.refresh() }
    fun delete(detail: TrackDetail) = action { repository.delete(requireNotNull(detail.track).id, detail.revision); saved["trackDetail"] = null; recorder.refresh() }
    fun editSegment(detail: TrackDetail, id: String, edit: SegmentEdit) = action { repository.editSegment(requireNotNull(detail.track).id, detail.revision, id, edit) }
    fun clearError() { mutable.update { it.copy(error = null) } }
    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch { try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: StaleRecordException) { mutable.update { it.copy(error = CommonR.string.record_stale) } }
            catch (_: IllegalArgumentException) { mutable.update { it.copy(error = R.string.track_invalid_edit) } }
            catch (_: Exception) { mutable.update { it.copy(error = CommonR.string.record_failed) } }
            finally { mutable.update { it.copy(busy = false) } } }
    }
}
