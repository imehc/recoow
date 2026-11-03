package imehc.recoow.feature.decision

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    var focusRequest by rememberSaveable { mutableStateOf<String?>(null) }
    when {
        optionDraft != null -> OptionEditor(vm, state, requireNotNull(optionDraft), back,
            onDelete = { option -> vm.deleteOption(option); back() },
            onFocus = { option -> focusRequest = option.id; back() })
        collectionDraft != null -> {
            val d = requireNotNull(collectionDraft)
            var attempted by rememberSaveable { mutableStateOf(false) }
            val titleError = if (attempted && d.title.isBlank()) stringResource(CommonR.string.record_field_required) else null
            RecordPage(stringResource(R.string.decision_collection), back, busy = state.busy, action = stringResource(CommonR.string.record_save),
                onAction = { if (d.title.isNotBlank()) vm.saveCollection() else attempted = true }) {
                item {
                    RecordForm {
                        RecordFormText(stringResource(CommonR.string.record_name), d.title, { vm.update(d.copy(title = it)) },
                            enabled = !state.busy, error = titleError, tag = "decision_collection_title")
                        RecordFormDivider()
                        RecordFormText(stringResource(CommonR.string.record_note), d.note, { vm.update(d.copy(note = it)) },
                            enabled = !state.busy, multiline = true)
                    }
                }
            }
        }
        detail != null -> ChoiceDetail(vm, state, state.data.history.firstOrNull { it.id == detail }, back)
        history -> ChoiceHistory(vm, state, collection, back)
        collection != null -> DecisionStage(vm, state, state.data.collections.firstOrNull { it.id == collection }, back,
            focusRequest = focusRequest, onFocusConsumed = { focusRequest = null })
        else -> Collections(vm, state, back)
    }
    RecordProblem(state.error, vm::clearError)
}
@Composable private fun Collections(vm: DecisionViewModel, state: DecisionState, back: () -> Unit) {
    RecordPage(stringResource(R.string.decision_title), back, tag = "tool_decisionMaker", busy = state.busy,
        more = listOf(RecoowAction(stringResource(R.string.decision_history), onClick = vm::showHistory)),
        add = if (!state.loading && !state.failed) stringResource(R.string.decision_add_collection) else null, onAdd = { vm.editCollection() }) {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed) {
            if (state.data.collections.isEmpty()) item {
                RecoowGroup {
                    RecoowEmptyState(
                        title = stringResource(R.string.decision_empty_title),
                    )
                }
            } else item(key = "collections") {
                RecoowGroup {
                    state.data.collections.forEachIndexed { index, collection ->
                        RecoowRow(
                            title = collection.title,
                            subtitle = collection.note,
                            trailingText = stringResource(R.string.decision_option_count, state.data.options.count { it.collectionId == collection.id && it.enabled }),
                            showChevron = true,
                            onClick = { vm.open(collection.id) },
                            modifier = Modifier.testTag("decision_${collection.id}"),
                        )
                        if (index < state.data.collections.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DecisionStage(vm: DecisionViewModel, state: DecisionState, collection: DecisionCollection?, back: () -> Unit,
    focusRequest: String? = null, onFocusConsumed: () -> Unit = {}) {
    val resultId by vm.result.collectAsStateWithLifecycle(); val result = state.data.history.firstOrNull { it.id == resultId }
    val options = state.data.options.filter { it.collectionId == collection?.id }; val enabled = options.filter { it.enabled }
    val cursor = remember(collection?.id) { Animatable(0f) }; val scope = rememberCoroutineScope()
    var spinning by remember { mutableStateOf(false) }; val drawn by vm.shownResult.collectAsStateWithLifecycle()
    var animate by rememberSaveable { mutableStateOf(true) }; var drawer by rememberSaveable { mutableIntStateOf(0) }
    var unavailable by remember { mutableStateOf(false) }; var loaded by remember { mutableStateOf(false) }
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
    LaunchedEffect(focusRequest, pool.size) {
        val id = focusRequest ?: return@LaunchedEffect
        val at = pool.indexOfFirst { it.id == id }
        if (at >= 0) cursor.snapTo(at.toFloat())
        onFocusConsumed()
    }
    fun browse(delta: Int) { if (!busy && pool.isNotEmpty()) scope.launch { cursor.snapTo((cursor.value + delta).mod(pool.size.toFloat())) } }
    var deletingCollection by remember { mutableStateOf(false) }
    val active = collection
    Scaffold(Modifier.testTag("decision_stage"), containerColor = MaterialTheme.colorScheme.background, topBar = {
        RecoowCompactTopBar(
            title = active?.title ?: stringResource(R.string.decision_title),
            navigationIcon = {
                IconButton(onClick = back, enabled = !state.busy) {
                    Icon(painterResource(CommonR.drawable.ic_record_back), stringResource(CommonR.string.record_back))
                }
            },
            action = {
                RecoowOverflowMenu(
                    listOfNotNull(
                        RecoowAction(stringResource(R.string.decision_history), enabled = !busy) { drawer = if (drawer == 2) 0 else 2 },
                        active?.let { value -> RecoowAction(stringResource(CommonR.string.record_edit), enabled = !state.busy) { vm.editCollection(value) } },
                        active?.let { RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy) { deletingCollection = true } },
                    ),
                    tag = "decision_more",
                )
            },
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.sectionGap),
        ) {
            if (active == null) item(key = "missing") {
                if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry)
                else RecoowGroup { RecoowEmptyState(stringResource(CommonR.string.record_missing)) }
            } else {
                item(key = "scene") {
                    Column {
                        val sceneShape = RoundedCornerShape(RecoowLayout.cardRadius)
                        Box(Modifier.fillMaxWidth().height(if (drawer != 0) 220.dp else 320.dp)
                            .clip(sceneShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, sceneShape)
                            .testTag(if (loaded) "decision_scene_ready" else "decision_scene_loading")) {
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
                        }
                    }
                }
                if (unavailable) item(key = "scene_failed") {
                    RecoowNotice(
                        stringResource(R.string.decision_scene_failed),
                        actions = listOf(RecoowAction(stringResource(CommonR.string.record_retry)) { unavailable = false; loaded = false }),
                    )
                }
                item(key = "focus") {
                    RecoowGroup {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { browse(-1) }, enabled = !busy && enabled.size > 1) { Text(stringResource(R.string.decision_previous)) }
                            Text(
                                focused?.title ?: stringResource(R.string.decision_no_options),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { browse(1) }, enabled = !busy && enabled.size > 1) { Text(stringResource(R.string.decision_next)) }
                        }
                    }
                }
                if (result != null && !spinning) item(key = "result") {
                    RecoowGroup {
                        RecoowRow(
                            title = stringResource(R.string.decision_result),
                            subtitle = result.title,
                            trailingText = stringResource(R.string.decision_view_result),
                            showChevron = true,
                            onClick = { vm.show(result.id) },
                        )
                    }
                }
                item(key = "controls") {
                    RecoowGroup {
                        RecoowSwitchRow(stringResource(R.string.decision_animation), animate, onChange = { animate = it }, enabled = !busy)
                        RecoowDivider(RecoowLayout.dividerInset)
                        RecoowRow(
                            title = stringResource(if (drawer == 1) R.string.decision_hide_options else R.string.decision_options),
                            showChevron = true,
                            onClick = { drawer = if (drawer == 1) 0 else 1 },
                            modifier = Modifier.testTag("decision_options_toggle"),
                        )
                        RecoowDivider(RecoowLayout.dividerInset)
                        RecoowRow(title = stringResource(R.string.decision_add_option), showChevron = true, onClick = { vm.editOption() })
                    }
                }
                if (drawer == 2) {
                    val rows = state.data.history.filter { it.collectionId == active.id }
                    if (rows.isEmpty()) item(key = "history_empty") {
                        RecoowGroup { RecoowEmptyState(stringResource(CommonR.string.record_empty)) }
                    } else item(key = "history") {
                        RecoowGroup {
                            rows.forEachIndexed { index, choice ->
                                RecoowRow(
                                    title = choice.title,
                                    subtitle = recordDateTime(choice.selectedAt),
                                    showChevron = true,
                                    onClick = { vm.show(choice.id) },
                                )
                                if (index < rows.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
                            }
                        }
                    }
                }
                if (drawer == 1) {
                    if (options.isEmpty()) item(key = "options_empty") {
                        RecoowGroup { RecoowEmptyState(stringResource(R.string.decision_no_options)) }
                    } else item(key = "options") {
                        RecoowGroup {
                            options.forEachIndexed { index, option ->
                                RecoowRow(
                                    title = option.title,
                                    subtitle = option.detail ?: option.customInfo ?: if (!option.enabled) stringResource(R.string.decision_disabled) else null,
                                    trailingText = stringResource(R.string.decision_weight_value, option.weight),
                                    showChevron = true,
                                    onClick = { vm.editOption(option) },
                                    modifier = Modifier.testTag("option_${option.id}"),
                                )
                                if (index < options.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
                            }
                        }
                    }
                }
            }
        }
    }
    if (deletingCollection && active != null) {
        RecordDeleteConfirmation(active.title, stringResource(R.string.decision_delete_collection), { deletingCollection = false }) {
            deletingCollection = false; vm.deleteCollection(active); back()
        }
    }
}
@Composable private fun OptionEditor(vm: DecisionViewModel, state: DecisionState, d: OptionDraft, back: () -> Unit,
    onDelete: (DecisionOption) -> Unit = {}, onFocus: (DecisionOption) -> Unit = {}) {
    val weight = d.weight.toLongOrNull(); val original = state.data.options.firstOrNull { it.id == d.id }
    val valid = d.valid && (weight in 1L..100L || weight == original?.weight)
    var attempted by rememberSaveable { mutableStateOf(false) }
    val titleError = if (attempted && d.title.isBlank()) stringResource(CommonR.string.record_field_required) else null
    val weightError = if (attempted && !(weight in 1L..100L || weight == original?.weight)) stringResource(R.string.decision_weight_invalid) else null
    RecordPage(stringResource(R.string.decision_option), back, busy = state.busy, action = stringResource(CommonR.string.record_save),
        more = if (original != null) listOf(RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy, onClick = { onDelete(original) })) else emptyList(),
        onAction = { if (valid && !state.failed && !state.loading) vm.saveOption() else attempted = true }) {
        item {
            RecordForm {
                RecordFormText(stringResource(CommonR.string.record_name), d.title, { vm.update(d.copy(title = it)) },
                    enabled = !state.busy, error = titleError, tag = "decision_option_title")
                RecordFormDivider()
                RecordFormText(stringResource(R.string.decision_description), d.detail, { vm.update(d.copy(detail = it)) },
                    enabled = !state.busy, multiline = true)
                RecordFormDivider()
                RecordFormText(stringResource(R.string.decision_custom), d.customInfo, { vm.update(d.copy(customInfo = it)) },
                    enabled = !state.busy, multiline = true)
                RecordFormDivider()
                RecordFormText(stringResource(R.string.decision_weight), d.weight, { vm.update(d.copy(weight = it)) },
                    enabled = !state.busy, keyboard = androidx.compose.ui.text.input.KeyboardType.Number, error = weightError, tag = "decision_weight")
                RecordFormDivider()
                RecordSwitch(stringResource(R.string.decision_enabled), d.enabled, { vm.update(d.copy(enabled = it)) },
                    enabled = !state.busy, modifier = Modifier.padding(horizontal = 14.dp))
            }
        }
        item { PhotoInput(d.image, { photo -> vm.update(d.copy(imageChanged = true, imageAssetId = photo?.assetId, imageContent = photo?.content, hasImage = photo != null)) }, vm.media, vm.processor, state.preferences) }
        if (original != null) {
            if (original.enabled) item(key = "focus") {
                RecoowGroup {
                    RecoowRow(title = stringResource(R.string.decision_focus), showChevron = true, onClick = { onFocus(original) })
                }
            }
        }
    }
}
@Composable private fun ChoiceHistory(vm: DecisionViewModel, state: DecisionState, collection: String?, back: () -> Unit) {
    val records = state.data.history.filter { collection == null || it.collectionId == collection }
    RecordPage(stringResource(R.string.decision_history), back, tag = "decision_history") {
        item { RecordLoadState(state.loading, state.failed, vm::retry) }
        if (!state.loading && !state.failed && records.isEmpty()) item {
            RecoowGroup { RecoowEmptyState(stringResource(CommonR.string.record_empty)) }
        } else if (records.isNotEmpty()) item(key = "rows") {
            RecoowGroup {
                records.forEachIndexed { index, record ->
                    RecoowRow(
                        title = record.title,
                        subtitle = listOfNotNull(record.collectionTitle, recordDateTime(record.selectedAt)).joinToString(" · "),
                        showChevron = true,
                        onClick = { vm.show(record.id) },
                    )
                    if (index < records.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
                }
            }
        }
    }
}
@Composable private fun ChoiceDetail(vm: DecisionViewModel, state: DecisionState, record: DecisionChoice?, back: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }; var preview by rememberSaveable { mutableStateOf(false) }
    RecordPage(record?.title ?: stringResource(R.string.decision_result), back, tag = "decision_detail", busy = state.busy,
        more = if (record != null) listOf(RecoowAction(stringResource(CommonR.string.record_delete), enabled = !state.busy, onClick = { deleting = true })) else emptyList()) {
        if (record == null) item(key = "missing") {
            if (state.loading || state.failed) RecordLoadState(state.loading, state.failed, vm::retry)
            else RecoowGroup { RecoowEmptyState(stringResource(CommonR.string.record_missing)) }
        } else {
            item(key = "facts") {
                RecoowGroup {
                    RecoowRow(title = stringResource(R.string.decision_collection), trailingText = record.collectionTitle)
                    RecoowDivider()
                    RecoowRow(title = stringResource(R.string.decision_selected_at), trailingText = recordDateTime(record.selectedAt))
                }
            }
            if (record.hasImage) item(key = "image") { MediaImage(record.image, vm.media, Modifier.fillMaxWidth().height(240.dp).clickable { preview = true }) }
            record.detail?.takeIf { it.isNotBlank() }?.let { text -> item(key = "detail") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.decision_description))
                    RecoowGroup { RecoowRow(title = text) }
                }
            } }
            record.customInfo?.takeIf { it.isNotBlank() }?.let { text -> item(key = "custom") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.decision_custom))
                    RecoowGroup { RecoowRow(title = text) }
                }
            } }
        }
    }
    if (preview && record != null) ImagePreview(listOf(record.image), 0, vm.media) { preview = false }
    if (deleting && record != null) RecordDeleteConfirmation(record.title, stringResource(R.string.decision_delete_history), { deleting = false }) { deleting = false; vm.deleteChoice(record) }
}
