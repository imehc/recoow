package imehc.recoow.feature.media

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.media.*
import imehc.recoow.core.media.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MediaLibraryScreen(viewModel: MediaLibraryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MediaLibraryContent(state, viewModel.repository, viewModel.processor, onBack, viewModel::retry,
        viewModel::clearError, viewModel::rename, viewModel::deleteSelected, viewModel::saveImage, viewModel::move, viewModel::reorder)
}

@Composable
fun MediaLibraryContent(state: MediaLibraryState, repository: MediaRepository, processor: ImageProcessor, onBack: () -> Unit,
    onRetry: () -> Unit, onClearError: () -> Unit, onRename: (String, String) -> Unit,
    onDelete: (Set<String>) -> Unit, onSaveImage: (String?, PreparedMedia) -> Unit,
    onMove: (String, Int) -> Unit, onReorder: (List<String>) -> Unit) {
    var grid by rememberSaveable { mutableStateOf(true) }
    var columns by rememberSaveable { mutableIntStateOf(3) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var replacing by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    var cropSource by rememberSaveable(stateSaver = PreparedMediaSaver) { mutableStateOf<PreparedMedia?>(null) }
    var cropId by rememberSaveable { mutableStateOf<String?>(null) }
    var cropError by remember { mutableStateOf(false) }
    var preparingCrop by remember { mutableStateOf(false) }
    var draftOrder by remember { mutableStateOf<List<String>?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val enabled = !state.busy && !preparingCrop && !state.readFailed && !state.loading
    val eligible = state.assets.filter { it.references == 0 }.map { it.id }
    LaunchedEffect(state.assets) { selected = selected.filter { it in eligible } }
    LaunchedEffect(state.assets, state.busy) { if (!state.busy) draftOrder = null }
    val byId = state.assets.associateBy { it.id }
    val assets = draftOrder?.mapNotNull { byId[it] } ?: state.assets
    val move: (String, String) -> Unit = { from, to ->
        val ids = (draftOrder ?: state.assets.map { it.id }).toMutableList()
        val source = ids.indexOf(from); val target = ids.indexOf(to)
        if (source >= 0 && target >= 0) { ids.removeAt(source); ids.add(target, from); draftOrder = ids }
    }
    val drag = rememberPhotoGridDragState(gridState, move)
    val cancelSelection = { selecting = false; selected = emptyList<String>() }
    BackHandler(selecting) { cancelSelection() }
    val open: (MediaAsset) -> Unit = { asset ->
        if (selecting) {
            if (enabled && asset.references == 0) selected = if (asset.id in selected) selected - asset.id else selected + asset.id
        } else preview = asset.id
    }
    val crop: (MediaAsset) -> Unit = { asset ->
        preparingCrop = true
        scope.launch {
            try {
                val file = requireNotNull(repository.resolve(MediaLocator(asset.id)).file)
                cropSource = processor.prepare(android.net.Uri.fromFile(file)); cropId = asset.id
            } catch (e: CancellationException) { throw e } catch (_: Exception) { cropError = true }
            finally { preparingCrop = false }
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        RecoowCompactTopBar(if (selecting) stringResource(R.string.assets_selected, selected.size) else stringResource(R.string.media_library),
            navigationIcon = { IconButton(onClick = if (selecting) cancelSelection else onBack,
                modifier = Modifier.testTag("media_back")) {
                Icon(painterResource(if (selecting) CommonR.drawable.ic_close else CommonR.drawable.ic_record_back),
                    stringResource(if (selecting) R.string.cancel else R.string.back))
            } }, action = {
                if (selecting) {
                    RecoowOverflowMenu(listOf(RecoowAction(stringResource(R.string.assets_select_all), "assets_select_all", enabled) { selected = eligible },
                        RecoowAction(stringResource(R.string.assets_deselect_all), "assets_deselect_all", enabled) { selected = emptyList() }), tag = "media_selection_more")
                    IconButton(onClick = { deleting = selected }, enabled = enabled && selected.isNotEmpty(), modifier = Modifier.testTag("media_delete_selected")) {
                        Icon(painterResource(CommonR.drawable.ic_delete), stringResource(R.string.delete), tint =
                            if (enabled && selected.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    IconButton(onClick = { grid = !grid }, modifier = Modifier.testTag("media_view")) {
                        Icon(painterResource(if (grid) CommonR.drawable.ic_list else CommonR.drawable.ic_grid),
                            stringResource(if (grid) R.string.view_list else R.string.view_grid), tint = MaterialTheme.colorScheme.primary)
                    }
                    RecoowOverflowMenu(listOf(RecoowAction(stringResource(R.string.assets_select), "media_select", enabled && eligible.isNotEmpty()) {
                        selecting = true
                    }), tag = "media_more")
                }
            })
    }, floatingActionButton = {
        if (!selecting && enabled && state.preferences != null)
            RecoowFloatingAddAction(stringResource(R.string.add_photo), onClick = { replacing = null; picking = true }, tag = "media_add")
    }) { padding ->
        LazyVerticalGrid(if (grid) GridCells.Fixed(columns) else GridCells.Fixed(1),
            Modifier.fillMaxSize().padding(padding).testTag("media_grid")
                .photoGridDensity(columns, grid && !selecting && enabled) { columns = it }
                .photoGridReorder(drag, assets.map { it.id }, grid && !selecting && enabled, move) { commit ->
                    val ids = draftOrder
                    if (commit && ids != null && ids != state.assets.map { it.id }) onReorder(ids) else draftOrder = null
                }, state = gridState,
            contentPadding = PaddingValues(start = RecoowLayout.pagePadding, end = RecoowLayout.pagePadding, top = 0.dp,
                bottom = if (selecting) 24.dp else 96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
            item(key = "media_header", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    RecoowSectionHeader(stringResource(R.string.assets_section), trailing = state.assets.size.toString())
                    if (state.loading || state.busy || preparingCrop) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (selecting) Text(stringResource(R.string.assets_selection_hint), Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (grid && state.assets.isNotEmpty()) Text(stringResource(R.string.assets_gesture_hint), Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.readFailed) RecoowGroup { RecoowEmptyState(stringResource(R.string.failed), actionLabel = stringResource(R.string.retry), onAction = onRetry) }
                    else if (!state.loading && state.assets.isEmpty()) RecoowGroup { RecoowEmptyState(stringResource(R.string.empty_library),
                        icon = CommonR.drawable.ic_photo, description = stringResource(R.string.empty_library_detail),
                        actionLabel = stringResource(R.string.add_photo), onAction = { replacing = null; picking = true }) }
                }
            }
            itemsIndexed(assets, key = { _, asset -> asset.id }) { index, asset ->
                val menu: @Composable () -> Unit = { RecoowOverflowMenu(listOf(
                    RecoowAction(stringResource(R.string.rename), "asset_rename_${asset.id}", enabled) { renaming = asset.id; title = asset.title.orEmpty() },
                    RecoowAction(stringResource(R.string.replace), "asset_replace_${asset.id}", enabled) { replacing = asset.id; picking = true },
                    RecoowAction(stringResource(R.string.crop), "asset_crop_${asset.id}", enabled) { crop(asset) },
                    RecoowAction(stringResource(R.string.move_up), "asset_up_${asset.id}", enabled && index > 0) { onMove(asset.id, -1) },
                    RecoowAction(stringResource(R.string.move_down), "asset_down_${asset.id}", enabled && index < assets.lastIndex) { onMove(asset.id, 1) },
                    RecoowAction(stringResource(R.string.delete), "asset_delete_${asset.id}", enabled && asset.references == 0) { deleting = listOf(asset.id) }),
                    tag = "asset_more_${asset.id}", onImage = grid) }
                val selection: @Composable () -> Unit = {
                    Box(contentAlignment = Alignment.Center) {
                        if (grid) Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(4.dp)))
                        Checkbox(asset.id in selected, onCheckedChange = { open(asset) }, enabled = enabled && asset.references == 0,
                            modifier = Modifier.testTag("asset_select_${asset.id}"))
                    }
                }
                val modifier = Modifier.testTag("asset_${asset.id}").zIndex(if (drag.draggedId == asset.id) 1f else 0f)
                    .animateItem(placementSpec = if (drag.draggedId == asset.id) null else spring()).graphicsLayer {
                        val shift = drag.translation(asset.id)
                        translationX = shift.x; translationY = shift.y
                        scaleX = if (drag.draggedId == asset.id) 1.03f else 1f; scaleY = scaleX
                    }
                if (grid) Column(modifier) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(RecoowLayout.thumbnailRadius))
                        .background(MaterialTheme.colorScheme.surfaceContainer).clickable { open(asset) }) {
                        AssetImage(asset, repository, Modifier.fillMaxSize().alpha(if (selecting && asset.references > 0) 0.5f else 1f))
                        Box(Modifier.align(Alignment.TopEnd)) { if (selecting) selection() else menu() }
                    }
                    Text(asset.title ?: stringResource(R.string.untitled), Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AssetReferences(asset, Modifier.padding(start = 2.dp, top = 2.dp))
                } else RecoowGroup(modifier) {
                    Row(Modifier.fillMaxWidth().clickable { open(asset) }.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssetImage(asset, repository, Modifier.size(64.dp).clip(RoundedCornerShape(RecoowLayout.thumbnailRadius)))
                        Column(Modifier.weight(1f)) {
                            Text(asset.title ?: stringResource(R.string.untitled), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            AssetReferences(asset, Modifier.padding(top = 4.dp))
                        }
                        if (selecting) selection() else menu()
                    }
                }
            }
        }
    }
    if (picking && state.preferences != null) PhotoSourcePicker(processor, repository, state.preferences,
        onDismiss = { picking = false }, onPicked = { it.content?.let { content -> onSaveImage(replacing, content) } }, independent = true)
    cropSource?.let { source -> PhotoCropEditor(source, processor,
        onCancel = { source.file.delete(); cropSource = null },
        onConfirm = { onSaveImage(cropId, it); source.file.delete(); cropSource = null }) }
    preview?.let { id ->
        val index = state.assets.indexOfFirst { it.id == id }
        if (index >= 0) ImagePreview(state.assets.map { MediaLocator(it.id) }, index, repository) { preview = null }
    }
    renaming?.let { id -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.rename)) },
        text = { RecordForm { RecordFormText(stringResource(R.string.title), title, { title = it }, placeholder = "", tag = "asset_title") } },
        confirmButton = { TextButton(onClick = { onRename(id, title); renaming = null }, enabled = enabled) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } }) }
    if (deleting.isNotEmpty()) AlertDialog(onDismissRequest = { deleting = emptyList() }, title = { Text(stringResource(R.string.delete_confirm)) },
        text = { Text(stringResource(R.string.assets_delete_info, deleting.size)) },
        confirmButton = { TextButton(onClick = { onDelete(deleting.toSet()); deleting = emptyList(); cancelSelection() },
            enabled = enabled && deleting.all { it in eligible }, modifier = Modifier.testTag("media_confirm_delete")) {
            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { deleting = emptyList() }) { Text(stringResource(R.string.cancel)) } })
    if (state.error != null || cropError) AlertDialog(onDismissRequest = { onClearError(); cropError = false },
        text = { Text(stringResource(state.error ?: R.string.failed)) },
        confirmButton = { TextButton(onClick = { onClearError(); cropError = false }) { Text(stringResource(R.string.confirm)) } })
}

@Composable private fun AssetImage(asset: MediaAsset, repository: MediaRepository, modifier: Modifier) {
    MediaImage(MediaLocator(asset.id), repository, modifier, fallback = {
        Icon(painterResource(CommonR.drawable.ic_photo), null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
    })
}

@Composable private fun AssetReferences(asset: MediaAsset, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(CommonR.drawable.ic_link), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
        Text(stringResource(R.string.assets_reference_count, asset.references), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
