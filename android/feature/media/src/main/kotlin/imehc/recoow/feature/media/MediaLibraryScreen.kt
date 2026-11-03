package imehc.recoow.feature.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.RecoowAddAction
import imehc.recoow.core.designsystem.RecoowCompactTopBar
import imehc.recoow.core.designsystem.RecoowDivider
import imehc.recoow.core.designsystem.RecoowEmptyState
import imehc.recoow.core.designsystem.RecoowGroup
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.RecoowSectionHeader
import imehc.recoow.core.designsystem.RecoowSwitchRow
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.media.*
import imehc.recoow.core.media.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaLibraryScreen(viewModel: MediaLibraryViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var grid by rememberSaveable { mutableStateOf(true) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var replacing by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    var cropSource by rememberSaveable(stateSaver = PreparedMediaSaver) { mutableStateOf<PreparedMedia?>(null) }
    var cropId by rememberSaveable { mutableStateOf<String?>(null) }
    var cropError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repository = viewModel.repository
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        RecoowCompactTopBar(stringResource(R.string.media_library), navigationIcon = {
            IconButton(onClick = onBack) { Icon(painterResource(CommonR.drawable.ic_record_back), stringResource(R.string.back)) }
        }, action = {
            if (!state.busy && state.preferences != null && !state.readFailed)
                RecoowAddAction(stringResource(R.string.add_photo), onClick = { replacing = null; picking = true }, tag = "media_add")
        })
    }) { padding ->
        LazyVerticalGrid(if (grid) GridCells.Adaptive(160.dp) else GridCells.Fixed(1),
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = RecoowLayout.pagePadding, end = RecoowLayout.pagePadding, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(RecoowLayout.sectionGap)) {
                    state.preferences?.let { prefs ->
                        Column {
                            RecoowSectionHeader(stringResource(R.string.photo_settings))
                            RecoowGroup {
                                RecoowSwitchRow(
                                    title = stringResource(R.string.add_to_assets),
                                    checked = prefs.addsPickedPhotosToMediaLibrary,
                                    onChange = viewModel::addToAssets,
                                    enabled = !state.busy,
                                )
                                RecoowDivider()
                                RecoowSwitchRow(
                                    title = stringResource(R.string.save_camera),
                                    checked = prefs.savesCameraPhotosToLibrary,
                                    onChange = viewModel::saveCamera,
                                    enabled = !state.busy,
                                )
                            }
                            Text(
                                stringResource(R.string.photo_settings_detail),
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column {
                        val hasAssets = state.assets.isNotEmpty()
                        RecoowSectionHeader(
                            stringResource(R.string.assets_section),
                            trailing = if (hasAssets) stringResource(if (grid) R.string.view_list else R.string.view_grid) else null,
                            onTrailingClick = if (hasAssets) ({ grid = !grid }) else null,
                        )
                        if (state.loading || state.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        } else if (state.readFailed) {
                            RecoowGroup {
                                RecoowEmptyState(
                                    title = stringResource(R.string.failed),
                                    actionLabel = stringResource(R.string.retry),
                                    onAction = viewModel::retry,
                                )
                            }
                        } else if (state.assets.isEmpty()) {
                            RecoowGroup {
                                RecoowEmptyState(
                                    title = stringResource(R.string.empty_library),
                                    actionLabel = stringResource(R.string.add_photo),
                                    onAction = { replacing = null; picking = true },
                                )
                            }
                        }
                    }
                }
            }
            itemsIndexed(state.assets, key = { _, asset -> asset.id }) { index, asset ->
                var menu by remember { mutableStateOf(false) }
                Surface(
                    modifier = Modifier.testTag("asset_${asset.id}"),
                    shape = RoundedCornerShape(RecoowLayout.cardRadius),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        MediaImage(
                            MediaLocator(asset.id), repository,
                            Modifier.fillMaxWidth().height(if (grid) 150.dp else 220.dp)
                                .clip(RoundedCornerShape(topStart = RecoowLayout.cardRadius, topEnd = RecoowLayout.cardRadius))
                                .clickable { preview = asset.id },
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    asset.title ?: stringResource(R.string.untitled),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = if (grid) 1 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(R.string.details, asset.mimeType, asset.width ?: 0, asset.height ?: 0, (asset.byteCount + 1023) / 1024),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!grid && asset.references > 0) Text(
                                    stringResource(R.string.references, asset.references),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            Box {
                                IconButton(onClick = { menu = true }, enabled = !state.busy) {
                                    Icon(
                                        painterResource(CommonR.drawable.ic_more),
                                        contentDescription = stringResource(R.string.more),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                DropdownMenu(
                                    expanded = menu,
                                    onDismissRequest = { menu = false },
                                    offset = DpOffset(x = 0.dp, y = 4.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 0.dp,
                                    shadowElevation = 6.dp,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = asset.id; title = asset.title.orEmpty() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.replace)) }, onClick = { menu = false; replacing = asset.id; picking = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.crop)) }, onClick = {
                                        menu = false
                                        scope.launch {
                                            try {
                                                val resolved = repository.resolve(MediaLocator(asset.id))
                                                val file = requireNotNull(resolved.file)
                                                cropSource = viewModel.processor.prepare(android.net.Uri.fromFile(file)); cropId = asset.id
                                            } catch (e: CancellationException) { throw e } catch (_: Exception) { cropError = true }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, enabled = index > 0,
                                        onClick = { menu = false; viewModel.move(asset.id, -1) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, enabled = index < state.assets.lastIndex,
                                        onClick = { menu = false; viewModel.move(asset.id, 1) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleting = asset.id })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (picking && state.preferences != null) PhotoSourcePicker(viewModel.processor, repository, state.preferences!!,
        onDismiss = { picking = false }, onPicked = { it.content?.let { content -> viewModel.saveImage(replacing, content) } }, independent = true)
    cropSource?.let { source -> PhotoCropEditor(source, viewModel.processor,
        onCancel = { source.file.delete(); cropSource = null },
        onConfirm = { viewModel.saveImage(cropId, it); source.file.delete(); cropSource = null }) }
    preview?.let { id ->
        val index = state.assets.indexOfFirst { it.id == id }
        if (index >= 0) ImagePreview(state.assets.map { MediaLocator(it.id) }, index, repository) { preview = null }
    }
    renaming?.let { id ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(title, onValueChange = { title = it }, label = { Text(stringResource(R.string.title)) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { viewModel.rename(id, title); renaming = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_confirm)) },
            text = { Text(stringResource(R.string.delete_info)) },
            confirmButton = { TextButton(onClick = { viewModel.delete(id); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (state.error != null || cropError) AlertDialog(onDismissRequest = { viewModel.clearError(); cropError = false },
        text = { Text(stringResource(state.error ?: R.string.failed)) },
        confirmButton = { TextButton(onClick = { viewModel.clearError(); cropError = false }) { Text(stringResource(R.string.confirm)) } })
}
