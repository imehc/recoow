package imehc.recoow.feature.tracks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.tracks.*
import imehc.recoow.core.location.RecordingState

@Composable internal fun TrackHistory(vm: TracksViewModel, state: TracksState, recording: RecordingState, back: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<TrackStatus?>(null) }
    val focus = LocalFocusManager.current; val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = { searching = false; vm.query(""); focus.clearFocus(); keyboard?.hide() }
    val clear: () -> Unit = { status = null; closeSearch() }
    BackHandler(searching) { closeSearch() }
    val rows = state.tracks.filter { (status == null || it.status == status) && (it.name + " " + it.note.orEmpty()).contains(query.trim(), true) }
    RecordPage(stringResource(R.string.track_history), back, tag = "track_history", busy = state.busy, compact = true,
        searching = searching, onSearchToggle = { if (searching) closeSearch() else searching = true }, searchTag = "track_search_toggle",
        searchQuery = query, onSearchQuery = vm::query) {
        if (state.failed) item { RecordListFooter(failed = true, onRetry = vm::retry) }
        else if (state.loading && state.tracks.isEmpty()) item { RecordSkeleton(rows = 4, leading = true) }
        else {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item(key = "summary") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.track_record_summary))
                    RecoowGroup { RecoowMetricStrip(listOf(
                        RecoowMetric(stringResource(R.string.track_total), state.tracks.size.toString(), icon = CommonR.drawable.ic_tool_tracks),
                        RecoowMetric(stringResource(R.string.track_finished), state.tracks.count { it.status == TrackStatus.FINISHED }.toString(), icon = CommonR.drawable.ic_check),
                        RecoowMetric(stringResource(R.string.track_paused), state.tracks.count { it.status == TrackStatus.PAUSED }.toString(), icon = CommonR.drawable.ic_pause),
                        RecoowMetric(stringResource(R.string.track_recording), state.tracks.count { it.status == TrackStatus.RECORDING }.toString(), icon = CommonR.drawable.ic_location),
                    )) }
                }
            }
            item(key = "rows") {
                RecoowGroup {
                    RecordChoice(stringResource(R.string.track_history_count, rows.size), status,
                        listOf(null to stringResource(R.string.track_all)) + TrackStatus.entries.map { it to statusName(it) }, { status = it }, bordered = false)
                    RecoowDivider(inset = 0.dp)
                    if (rows.isEmpty()) RecoowEmptyState(
                        stringResource(if (state.tracks.isEmpty()) CommonR.string.record_empty else CommonR.string.record_no_matches),
                        description = stringResource(if (state.tracks.isEmpty()) R.string.track_history_hint else CommonR.string.record_no_matches_hint),
                        actionLabel = if (state.tracks.isEmpty()) null else stringResource(CommonR.string.record_clear_filters),
                        onAction = if (state.tracks.isEmpty()) null else clear)
                    rows.forEachIndexed { index, track ->
                        val active = recording.current?.takeIf { it.id == track.id }
                        TrackHistoryRow(track, active?.distance ?: track.distance, if (active != null) recording.duration else track.duration,
                            if (active != null) recording.pointCount else track.pointCount) { vm.show(track.id) }
                        if (index < rows.lastIndex) RecoowDivider(inset = RecoowLayout.coverDividerInset)
                    }
                }
            }
        }
    }
}

@Composable internal fun TrackEditor(vm: TracksViewModel, state: TracksState, draft: TrackDraft, back: () -> Unit) {
    var attempted by rememberSaveable { mutableStateOf(false) }
    val error = if (attempted && draft.name.isBlank()) stringResource(CommonR.string.record_field_required) else null
    RecordPage(stringResource(R.string.track_edit), back, tag = "track_form", busy = state.busy, compact = true,
        action = stringResource(CommonR.string.record_save), actionIcon = CommonR.drawable.ic_check,
        onAction = { if (draft.name.isNotBlank()) vm.save() else attempted = true }) {
        item { RecordFormSection(stringResource(R.string.track_basic_info)) {
            RecordFormText(stringResource(CommonR.string.record_name), draft.name, { vm.update(draft.copy(name = it)) },
                enabled = !state.busy, placeholder = "", error = error, tag = "track_name")
        } }
        item { RecordFormSection(stringResource(CommonR.string.record_note)) {
            RecordFormText(stringResource(CommonR.string.record_note), draft.note, { vm.update(draft.copy(note = it)) },
                enabled = !state.busy, multiline = true, placeholder = "", showLabel = false)
        } }
    }
}
