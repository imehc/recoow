package imehc.recoow.feature.decision

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.decision.*

@Composable internal fun DecisionStage(vm: DecisionViewModel, state: DecisionState, collection: DecisionCollection?, back: () -> Unit,
    dark: Boolean, onModels: () -> Unit, focusRequest: String? = null, onFocusConsumed: () -> Unit = {}) {
    val resultId by vm.result.collectAsStateWithLifecycle()
    val result = state.data.history.firstOrNull { it.id == resultId }
    val options = remember(state.data.options, collection?.id) { state.data.options.filter { it.collectionId == collection?.id } }
    val enabled = remember(options) { options.filter { it.enabled } }
    val drawn by vm.shownResult.collectAsStateWithLifecycle()
    val motion = rememberDecisionTurntableMotion()
    var spinning by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var spinPool by remember { mutableStateOf<List<DecisionOption>?>(null) }
    var inspected by remember { mutableStateOf<DecisionOption?>(null) }
    var deletingOption by remember { mutableStateOf<DecisionOption?>(null) }
    var deletingCollection by remember { mutableStateOf(false) }
    val pool = spinPool ?: enabled
    val focusIndex by remember(motion, pool.size) { derivedStateOf { Turntable.focused(motion.cursor.toDouble(), pool.size) } }
    val focused = focusIndex?.let { pool.getOrNull(it) }
    val busy = state.busy || spinning || result != null && drawn != result.id
    val resultFocused = result != null && !spinning && focused?.id == result.optionId
    val history = remember(state.data.history, collection?.id) { state.data.history.filter { it.collectionId == collection?.id } }
    LaunchedEffect(result?.id) {
        val selected = result ?: return@LaunchedEffect
        val snapshot = enabled.toList()
        val index = snapshot.indexOfFirst { it.id == selected.optionId }
        if (drawn == selected.id) { if (index >= 0) motion.snap(index, snapshot.size); return@LaunchedEffect }
        vm.markResultShown(selected.id)
        if (index < 0) return@LaunchedEffect
        spinPool = snapshot
        spinning = true
        try {
            val target = Turntable.draw(index, snapshot.size, motion.cursor.toDouble()).toFloat()
            motion.spin(target, true)
            motion.snap(index, snapshot.size)
        } finally { spinning = false; spinPool = null }
    }
    LaunchedEffect(enabled.map { it.id }, spinning) {
        if (!spinning && enabled.isNotEmpty()) motion.snap(Turntable.focused(motion.cursor.toDouble(), enabled.size) ?: 0, enabled.size)
    }
    LaunchedEffect(focusRequest, pool.map { it.id }) {
        val id = focusRequest ?: return@LaunchedEffect
        val index = pool.indexOfFirst { it.id == id }
        if (index >= 0) motion.browse(index, pool.size)
        onFocusConsumed()
    }
    Scaffold(Modifier.testTag("decision_stage"), containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RecoowCompactTopBar(collection?.title ?: stringResource(R.string.decision_title), navigationIcon = {
                IconButton(onClick = back, enabled = !state.busy) { Icon(painterResource(CommonR.drawable.ic_record_back), stringResource(CommonR.string.record_back)) }
            }, action = {
                IconButton(onClick = { tab = 1 }, enabled = !busy) {
                    Icon(painterResource(CommonR.drawable.ic_clock), stringResource(R.string.decision_history), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RecoowOverflowMenu(listOfNotNull(
                    RecoowAction(stringResource(R.string.decision_models), enabled = !busy, onClick = onModels),
                    collection?.let { c -> RecoowAction(stringResource(CommonR.string.record_edit), enabled = !busy) { vm.editCollection(c) } },
                    collection?.let { RecoowAction(stringResource(CommonR.string.record_delete), enabled = !busy) { deletingCollection = true } },
                ), tag = "decision_more")
            })
        },
        floatingActionButton = {
            if (collection != null) RecoowFloatingAddAction(stringResource(R.string.decision_add_option), onClick = { vm.editOption() }, enabled = !busy, tag = "decision_add_option")
        },
        bottomBar = {
            if (collection != null) RecoowBottomAction(stringResource(when {
                state.busy -> R.string.decision_preparing
                spinning -> R.string.decision_spinning
                result != null -> R.string.decision_redraw
                else -> R.string.decision_draw
            }), vm::choose, enabled = !busy && enabled.isNotEmpty(), modifier = Modifier.testTag("decision_draw"))
        }) { padding ->
        if (collection == null) Column(Modifier.padding(padding).padding(RecoowLayout.pagePadding)) {
            if (state.failed) RecordListFooter(failed = true, onRetry = vm::retry)
            else if (state.loading) RecordSkeleton(rows = 3)
            else RecoowGroup { RecoowEmptyState(stringResource(CommonR.string.record_missing)) }
        } else BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(horizontal = RecoowLayout.pagePadding)) {
            val sceneHeight = (maxHeight * 0.40f).coerceIn(160.dp, 280.dp)
            val titles = remember(pool) { pool.map { it.title } }
            Column(Modifier.fillMaxSize().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
                DecisionTurntablePanel(titles, motion, dark, sceneHeight, interactive = !busy)
                DecisionFocusRow(motion, pool, focusIndex, focused, resultFocused, spinning, busy,
                    onInspect = { if (resultFocused) vm.show(requireNotNull(result).id) else inspected = focused })
                RecoowGroup(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(tab == 0, { tab = 0 }, label = { Text(stringResource(R.string.decision_options)) }, enabled = !busy)
                        FilterChip(tab == 1, { tab = 1 }, label = { Text(stringResource(R.string.decision_history)) }, enabled = !busy)
                        Spacer(Modifier.weight(1f))
                        Text(stringResource(if (tab == 0) R.string.decision_candidate_count else R.string.decision_history_count,
                            if (tab == 0) options.size else history.size), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RecoowDivider(inset = 0.dp)
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp)) {
                        if (tab == 0) {
                            if (options.isEmpty()) item { RecoowEmptyState(stringResource(R.string.decision_no_options), description = stringResource(R.string.decision_options_hint)) }
                            items(options, key = { it.id }) { option ->
                                DecisionOptionRow(vm, option, enabled = !busy, onBrowse = {
                                    val index = pool.indexOfFirst { it.id == option.id }
                                    if (index >= 0) motion.browse(index, pool.size) else inspected = option
                                }, onInspect = { inspected = option }, onEdit = { vm.editOption(option) }, onDelete = { deletingOption = option },
                                    selected = focused?.id == option.id)
                                RecoowDivider(inset = RecoowLayout.coverDividerInset)
                            }
                        } else {
                            if (history.isEmpty()) item { RecoowEmptyState(stringResource(CommonR.string.record_empty), description = stringResource(R.string.decision_history_hint)) }
                            items(history, key = { it.id }) { choice ->
                                DecisionChoiceRow(vm, choice, enabled = !busy) { vm.show(choice.id) }
                                RecoowDivider(inset = RecoowLayout.coverDividerInset)
                            }
                        }
                    }
                }
            }
        }
    }
    inspected?.let { option -> DecisionCandidateSheet(vm, option, onDismiss = { inspected = null },
        onEdit = { inspected = null; vm.editOption(option) }, onDelete = { inspected = null; deletingOption = option }) }
    deletingOption?.let { option -> RecordDeleteConfirmation(option.title, stringResource(R.string.decision_delete_option), { deletingOption = null }) {
        deletingOption = null; vm.deleteOption(option)
    } }
    if (deletingCollection && collection != null) RecordDeleteConfirmation(collection.title, stringResource(R.string.decision_delete_collection), { deletingCollection = false }) {
        deletingCollection = false; vm.deleteCollection(collection)
    }
}

@Composable private fun DecisionFocusRow(motion: DecisionTurntableMotion, pool: List<DecisionOption>, focusIndex: Int?,
    focused: DecisionOption?, resultFocused: Boolean, spinning: Boolean, busy: Boolean, onInspect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { motion.browse((focusIndex ?: 0) - 1, pool.size) }, enabled = !busy && pool.size > 1) {
            Icon(painterResource(CommonR.drawable.ic_record_back), stringResource(R.string.decision_previous), Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).clickable(enabled = !busy && focused != null, onClick = onInspect),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(focused?.title ?: stringResource(R.string.decision_no_options), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (focused != null) Text(stringResource(when {
                spinning -> R.string.decision_passing
                resultFocused -> R.string.decision_result_detail
                else -> R.string.decision_current_detail
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { motion.browse((focusIndex ?: 0) + 1, pool.size) }, enabled = !busy && pool.size > 1) {
            Icon(painterResource(CommonR.drawable.ic_chevron_right), stringResource(R.string.decision_next), Modifier.size(20.dp))
        }
    }
}
