package imehc.recoow.feature.decision

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.decision.*
import imehc.recoow.core.domain.preferences.Appearance
import imehc.recoow.core.media.*
import imehc.recoow.core.scene.*
import kotlinx.coroutines.launch
import kotlin.math.pow

@Composable fun DecisionScreen(vm: DecisionViewModel, onBack: () -> Unit, recordId: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle(); val collection by vm.collection.collectAsStateWithLifecycle()
    val collectionDraft by vm.collectionDraft.collectAsStateWithLifecycle(); val optionDraft by vm.optionDraft.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle(); val detail by vm.detail.collectAsStateWithLifecycle()
    var delivered by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(recordId) { if (recordId != null && recordId != delivered) { vm.show(recordId); delivered = recordId } }
    val context = LocalContext.current
    LaunchedEffect(context) { runCatching { prewarmDecisionScene(context) } }
    val back: () -> Unit = { if (!state.busy) { if (collection != null || collectionDraft != null || optionDraft != null || history || detail != null) vm.back() else onBack() } }
    BackHandler(collection != null || collectionDraft != null || optionDraft != null || history || detail != null || state.busy, back)
    when {
        optionDraft != null -> OptionEditor(vm, state, requireNotNull(optionDraft), back)
        collectionDraft != null -> {
            val d = requireNotNull(collectionDraft)
            RecordPage(stringResource(R.string.decision_collection), back, busy = state.busy, action = stringResource(CommonR.string.record_save), actionEnabled = d.title.isNotBlank(), onAction = vm::saveCollection) {
                item { RecordField(stringResource(CommonR.string.record_name), d.title, { vm.update(d.copy(title = it)) }, !state.busy, tag = "decision_collection_title") }
                item { RecordField(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) }, !state.busy, multiline = true) }
            }
        }
        detail != null -> ChoiceDetail(vm, state, state.data.history.firstOrNull { it.id == detail }, back)
        history -> ChoiceHistory(vm, state, collection, back)
        collection != null -> DecisionStage(vm, state, state.data.collections.firstOrNull { it.id == collection }, back)
        else -> Collections(vm, state, back)
    }
    RecordProblem(state.error, vm::clearError)
}
@Composable private fun Collections(vm: DecisionViewModel, state: DecisionState, back: () -> Unit) {
    var deleting by remember { mutableStateOf<DecisionCollection?>(null) }
    RecordPage(stringResource(R.string.decision_title), back, tag = "tool_decisionMaker", busy = state.busy,
        action = stringResource(R.string.decision_history), onAction = vm::showHistory,
        add = if (!state.loading && !state.failed) stringResource(R.string.decision_add_collection) else null, onAdd = { vm.editCollection() }) {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            if (state.data.collections.isEmpty()) item { Text(stringResource(R.string.decision_empty)) }
            items(state.data.collections, key = { it.id }) { collection -> Card(Modifier.fillMaxWidth().clickable { vm.open(collection.id) }) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(collection.title, style = MaterialTheme.typography.titleMedium); collection.note?.let { Text(it) }
                Text(stringResource(R.string.decision_option_count, state.data.options.count { it.collectionId == collection.id && it.enabled }))
                FlowRow { TextButton(onClick = { vm.editCollection(collection) }, enabled = !state.busy) { Text(stringResource(CommonR.string.record_edit)) }
                    TextButton(onClick = { deleting = collection }, enabled = !state.busy) { Text(stringResource(CommonR.string.record_delete)) } }
            } } }
        }
    }
    deleting?.let { value -> RecordDeleteConfirmation(value.title, stringResource(R.string.decision_delete_collection), { deleting = null }) { deleting = null; vm.deleteCollection(value) } }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DecisionStage(vm: DecisionViewModel, state: DecisionState, collection: DecisionCollection?, back: () -> Unit) {
    val resultId by vm.result.collectAsStateWithLifecycle(); val result = state.data.history.firstOrNull { it.id == resultId }
    val options = state.data.options.filter { it.collectionId == collection?.id }; val enabled = options.filter { it.enabled }
    val cursor = remember(collection?.id) { Animatable(0f) }; val scope = rememberCoroutineScope()
    var spinning by remember { mutableStateOf(false) }; val drawn by vm.shownResult.collectAsStateWithLifecycle()
    var animate by rememberSaveable { mutableStateOf(true) }; var drawer by rememberSaveable { mutableIntStateOf(0) }
    var unavailable by remember { mutableStateOf(false) }; var loaded by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DecisionOption?>(null) }
    var spinPool by remember { mutableStateOf<List<DecisionOption>?>(null) }
    val pool = spinPool ?: enabled
    val focused = Turntable.focused(cursor.value.toDouble(), pool.size)?.let { pool[it] }
    val dark = when (state.preferences.appearance) { Appearance.DARK -> true; Appearance.LIGHT -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    LaunchedEffect(result?.id) {
        val selected = result ?: return@LaunchedEffect
        val snapshot = enabled.toList(); val index = snapshot.indexOfFirst { it.id == selected.optionId }
        if (drawn == selected.id) { if (index >= 0) cursor.snapTo(index.toFloat()); return@LaunchedEffect }
        vm.markResultShown(selected.id)
        if (index < 0) return@LaunchedEffect
        spinPool = snapshot; spinning = true
        try {
            val target = Turntable.draw(index, snapshot.size, cursor.value.toDouble()).toFloat()
            if (animate && ValueAnimator.areAnimatorsEnabled()) cursor.animateTo(target, tween(2200, easing = Easing { t -> 1f - (1f - t).pow(5) * (1f + 5f * t) }))
            cursor.snapTo(index.toFloat())
        } finally { spinning = false; spinPool = null }
    }
    LaunchedEffect(enabled.map { it.id }, spinning) { if (!spinning && enabled.isNotEmpty()) cursor.snapTo(Turntable.wrap(cursor.value.toInt(), enabled.size).toFloat()) }
    val busy = state.busy || spinning || result != null && drawn != result.id
    fun browse(delta: Int) { if (!busy && pool.isNotEmpty()) scope.launch { cursor.snapTo((cursor.value + delta).mod(pool.size.toFloat())) } }
    Scaffold(Modifier.testTag("decision_stage"), topBar = { TopAppBar(title = { Text(collection?.title ?: stringResource(R.string.decision_title)) }, navigationIcon = {
        TextButton(onClick = back, enabled = !state.busy) { Text(stringResource(CommonR.string.record_back)) }
    }, actions = { TextButton(onClick = { drawer = if (drawer == 2) 0 else 2 }, enabled = !busy) { Text(stringResource(R.string.decision_history)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (collection == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_missing)) }
            else {
                item { Box(Modifier.fillMaxWidth().height(if (drawer != 0) 220.dp else 330.dp).testTag(if (loaded) "decision_scene_ready" else "decision_scene_loading")) {
                    if (!unavailable) DecisionScene(pool.map { it.title }, cursor.value.toDouble(), dark, Modifier.fillMaxSize(), onLoaded = { loaded = true }, onFailure = { unavailable = true })
                    Box(Modifier.matchParentSize().pointerInput(busy, pool.size) {
                        var drag = 0f
                        detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = { if (kotlin.math.abs(drag) > 36f) browse(if (drag < 0) 1 else -1) }) { change, amount ->
                            if (!busy) { change.consume(); drag += amount }
                        }
                    })
                    FilledTonalButton(onClick = vm::choose, enabled = !busy && enabled.isNotEmpty(), modifier = Modifier.align(Alignment.Center)) {
                        Text(stringResource(if (state.busy) R.string.decision_preparing else if (spinning) R.string.decision_spinning else R.string.decision_draw))
                    }
                    if (!loaded && !unavailable) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).size(24.dp))
                } }
                if (unavailable) item { Text(stringResource(R.string.decision_scene_failed)); TextButton(onClick = { unavailable = false; loaded = false }) { Text(stringResource(CommonR.string.record_retry)) } }
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { browse(-1) }, enabled = !busy && enabled.size > 1) { Text(stringResource(R.string.decision_previous)) }
                    Text(focused?.title ?: stringResource(R.string.decision_no_options), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { browse(1) }, enabled = !busy && enabled.size > 1) { Text(stringResource(R.string.decision_next)) }
                } }
                if (result != null && !spinning) item { Card(Modifier.fillMaxWidth().clickable { vm.show(result.id) }) { Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.decision_result), style = MaterialTheme.typography.labelLarge); Text(result.title, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.decision_view_result))
                } } }
                item { RecordSwitch(stringResource(R.string.decision_animation), animate, { animate = it }, enabled = !busy)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { drawer = if (drawer == 1) 0 else 1 }, enabled = !busy) { Text(stringResource(if (drawer == 1) R.string.decision_hide_options else R.string.decision_options)) }
                        FilledTonalButton(onClick = { vm.editOption() }, enabled = !busy) { Text(stringResource(R.string.decision_add_option)) }
                    }
                }
                if (drawer == 2) {
                    val history = state.data.history.filter { it.collectionId == collection.id }
                    if (history.isEmpty()) item { Text(stringResource(CommonR.string.record_empty)) }
                    items(history, key = { "history:${it.id}" }) { choice -> OutlinedCard(Modifier.fillMaxWidth().clickable { vm.show(choice.id) }) { Column(Modifier.padding(16.dp)) {
                        Text(choice.title, style = MaterialTheme.typography.titleMedium); Text(recordDateTime(choice.selectedAt))
                    } } }
                }
                if (drawer == 1) {
                    if (options.isEmpty()) item { Text(stringResource(R.string.decision_no_options)) }
                    items(options, key = { it.id }) { option -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (option.hasImage) MediaImage(option.image, vm.media, Modifier.size(100.dp))
                        Text(option.title, style = MaterialTheme.typography.titleMedium); option.detail?.let { Text(it) }; option.customInfo?.let { Text(it) }
                        Text(stringResource(R.string.decision_weight_value, option.weight)); if (!option.enabled) Text(stringResource(R.string.decision_disabled))
                        FlowRow { TextButton(onClick = { vm.editOption(option) }, enabled = !busy) { Text(stringResource(CommonR.string.record_edit)) }
                            TextButton(onClick = { deleting = option }, enabled = !busy) { Text(stringResource(CommonR.string.record_delete)) }
                            if (option.enabled) TextButton(onClick = { scope.launch { cursor.snapTo(enabled.indexOfFirst { it.id == option.id }.toFloat()) } }, enabled = !busy) { Text(stringResource(R.string.decision_focus)) } }
                    } } }
                }
            }
        }
    }
    deleting?.let { value -> RecordDeleteConfirmation(value.title, stringResource(R.string.decision_delete_option), { deleting = null }) { deleting = null; vm.deleteOption(value) } }
}
@Composable private fun OptionEditor(vm: DecisionViewModel, state: DecisionState, d: OptionDraft, back: () -> Unit) {
    val weight = d.weight.toLongOrNull(); val original = state.data.options.firstOrNull { it.id == d.id }
    val valid = d.valid && (weight in 1L..100L || weight == original?.weight)
    RecordPage(stringResource(R.string.decision_option), back, busy = state.busy, action = stringResource(CommonR.string.record_save), actionEnabled = valid && !state.failed && !state.loading, onAction = vm::saveOption) {
        item { RecordField(stringResource(CommonR.string.record_name), d.title, { vm.update(d.copy(title = it)) }, !state.busy, tag = "decision_option_title") }
        item { RecordField(stringResource(R.string.decision_description), d.detail, { vm.update(d.copy(detail = it)) }, !state.busy, multiline = true) }
        item { RecordField(stringResource(R.string.decision_custom), d.customInfo, { vm.update(d.copy(customInfo = it)) }, !state.busy, multiline = true) }
        item { RecordField(stringResource(R.string.decision_weight), d.weight, { vm.update(d.copy(weight = it)) }, !state.busy, tag = "decision_weight") }
        item { RecordSwitch(stringResource(R.string.decision_enabled), d.enabled, { vm.update(d.copy(enabled = it)) }, enabled = !state.busy) }
        item { PhotoInput(d.image, { photo -> vm.update(d.copy(imageChanged = true, imageAssetId = photo?.assetId, imageContent = photo?.content, hasImage = photo != null)) }, vm.media, vm.processor, state.preferences) }
    }
}
@Composable private fun ChoiceHistory(vm: DecisionViewModel, state: DecisionState, collection: String?, back: () -> Unit) {
    val records = state.data.history.filter { collection == null || it.collectionId == collection }
    RecordPage(stringResource(R.string.decision_history), back, tag = "decision_history") {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed && records.isEmpty()) item { Text(stringResource(CommonR.string.record_empty)) }
        items(records, key = { it.id }) { record -> Card(Modifier.fillMaxWidth().clickable { vm.show(record.id) }) { Column(Modifier.padding(16.dp)) {
            Text(record.title, style = MaterialTheme.typography.titleMedium); Text(record.collectionTitle); Text(recordDateTime(record.selectedAt), style = MaterialTheme.typography.bodySmall)
        } } }
    }
}
@Composable private fun ChoiceDetail(vm: DecisionViewModel, state: DecisionState, record: DecisionChoice?, back: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }; var preview by rememberSaveable { mutableStateOf(false) }
    RecordPage(record?.title ?: stringResource(R.string.decision_result), back, tag = "decision_detail", busy = state.busy) {
        if (record == null) item { if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry) else Text(stringResource(CommonR.string.record_missing)) }
        else {
            item { Text(record.collectionTitle); Text(recordDateTime(record.selectedAt)) }
            if (record.hasImage) item { MediaImage(record.image, vm.media, Modifier.fillMaxWidth().height(240.dp).clickable { preview = true }) }
            record.detail?.let { item { Text(it) } }; record.customInfo?.let { item { Text(it) } }
            item { TextButton(onClick = { deleting = true }, enabled = !state.busy) { Text(stringResource(CommonR.string.record_delete)) } }
        }
    }
    if (preview && record != null) ImagePreview(listOf(record.image), 0, vm.media) { preview = false }
    if (deleting && record != null) RecordDeleteConfirmation(record.title, stringResource(R.string.decision_delete_history), { deleting = false }) { deleting = false; vm.deleteChoice(record) }
}
