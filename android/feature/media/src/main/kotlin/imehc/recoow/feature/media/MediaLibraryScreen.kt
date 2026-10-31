package imehc.recoow.feature.media

import imehc.recoow.core.designsystem.RecordSwitch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.media_library)) }, navigationIcon = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        })
    }, floatingActionButton = {
        if (!state.busy && state.preferences != null && !state.readFailed) ExtendedFloatingActionButton(
            onClick = { replacing = null; picking = true }, modifier = Modifier.testTag("media_add")) { Text(stringResource(R.string.add_photo)) }
    }) { padding ->
        LazyVerticalGrid(if (grid) GridCells.Adaptive(160.dp) else GridCells.Fixed(1),
            Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.preferences?.let { prefs ->
                        Card {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.photo_settings), style = MaterialTheme.typography.titleMedium)
                                PhotoPreference(stringResource(R.string.add_to_assets), prefs.addsPickedPhotosToMediaLibrary, !state.busy, viewModel::addToAssets)
                                PhotoPreference(stringResource(R.string.save_camera), prefs.savesCameraPhotosToLibrary, !state.busy, viewModel::saveCamera)
                                Text(stringResource(R.string.photo_settings_detail), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(grid, onClick = { grid = true }, label = { Text(stringResource(R.string.grid)) })
                        FilterChip(!grid, onClick = { grid = false }, label = { Text(stringResource(R.string.list)) })
                    }
                    if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.readFailed) TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                    else if (!state.loading && state.assets.isEmpty()) Text(stringResource(R.string.empty_library), Modifier.padding(vertical = 24.dp))
                }
            }
            itemsIndexed(state.assets, key = { _, asset -> asset.id }) { index, asset ->
                var menu by remember { mutableStateOf(false) }
                Card(Modifier.testTag("asset_${asset.id}")) {
                    MediaImage(MediaLocator(asset.id), repository, Modifier.fillMaxWidth().height(if (grid) 160.dp else 220.dp).clickable { preview = asset.id })
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(asset.title ?: stringResource(R.string.untitled), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.references, asset.references), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.details, asset.mimeType, asset.width ?: 0, asset.height ?: 0, (asset.byteCount + 1023) / 1024), style = MaterialTheme.typography.bodySmall)
                        Box {
                            TextButton(onClick = { menu = true }, enabled = !state.busy) { Text(stringResource(R.string.more)) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
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

@Composable
private fun PhotoPreference(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    RecordSwitch(label, checked, onChange, enabled = enabled)
}
