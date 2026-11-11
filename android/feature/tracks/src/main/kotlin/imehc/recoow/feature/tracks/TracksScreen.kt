package imehc.recoow.feature.tracks

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.RecordProblem
import imehc.recoow.core.designsystem.recordBackAction
import imehc.recoow.core.domain.navigation.ToolRoute

@Composable fun TracksScreen(vm: TracksViewModel, onBack: () -> Unit, recordId: String? = null, onSource: (ToolRoute, String) -> Unit = { _, _ -> }) {
    val state by vm.state.collectAsStateWithLifecycle(); val detailId by vm.detailId.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(); val draft by vm.draft.collectAsStateWithLifecycle()
    val recording by vm.recorder.state.collectAsStateWithLifecycle()
    var delivered by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) { if (recordId != null && recordId != delivered) { vm.show(recordId); delivered = recordId } }
    LaunchedEffect(recording.finishedId) { recording.finishedId?.let { vm.show(it); vm.recorder.dismissFinished() } }
    val back = recordBackAction(state.busy, draft != null || detailId != null || history,
        entryDetail = draft == null && detailId != null, onNestedBack = vm::back, onBack = onBack)
    BackHandler(draft != null || detailId != null || history || state.busy, back)
    when {
        draft != null -> TrackEditor(vm, state, requireNotNull(draft), back)
        detailId != null -> key(detailId) { TrackDetails(vm, state, back, onSource) }
        history -> TrackHistory(vm, state, recording, back)
        else -> RecordingPage(vm, state, recording, back)
    }
    RecordProblem(state.error, vm::clearError)
}
