package imehc.recoow.core.media

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.preferences.AppPreferences
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class SelectedPhoto(val content: PreparedMedia? = null, val assetId: String? = null)

/** No mutation reaches the owner until onPicked; dismissing the picker/editor preserves its prior image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSourcePicker(processor: ImageProcessor, repository: MediaRepository, preferences: AppPreferences,
    onDismiss: () -> Unit, onPicked: (SelectedPhoto) -> Unit, independent: Boolean = false) {
    var uri by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var camera by rememberSaveable { mutableStateOf(false) }
    var galleryAllowed by rememberSaveable { mutableStateOf(false) }
    var galleryHandled by rememberSaveable { mutableStateOf(false) }
    var preparedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var width by rememberSaveable { mutableIntStateOf(0) }
    var height by rememberSaveable { mutableIntStateOf(0) }
    var pickingAsset by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val prepareUri: (Boolean) -> Unit = { allowed ->
        galleryAllowed = allowed
        uri = cameraPath?.let { processor.cameraUri(File(it)).toString() }
    }
    val galleryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) error = R.string.permission_gallery
        prepareUri(allowed)
    }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            camera = true
            if (preferences.savesCameraPhotosToLibrary && Build.VERSION.SDK_INT <= 28) galleryPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else prepareUri(Build.VERSION.SDK_INT >= 29)
        } else { cameraPath?.let { File(it).delete() }; cameraPath = null }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selected ->
        if (selected != null) { camera = false; uri = selected.toString() }
    }
    LaunchedEffect(uri, preparedPath) {
        val source = uri ?: return@LaunchedEffect
        if (preparedPath != null) return@LaunchedEffect
        try {
            val content = processor.prepare(source.toUri())
            if (camera && preferences.savesCameraPhotosToLibrary && galleryAllowed && !galleryHandled) {
                galleryHandled = true
                try { processor.saveToGallery(content) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { error = R.string.gallery_failed }
            }
            width = content.width!!; height = content.height!!; preparedPath = content.file.path
            cameraPath?.let { File(it).delete() }; cameraPath = null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { error = R.string.failed; uri = null }
    }
    val finish: (PreparedMedia) -> Unit = { content ->
        saving = true
        scope.launch {
            try {
                if (!independent && preferences.addsPickedPhotosToMediaLibrary) {
                    val id = repository.createImage(content)
                    onPicked(SelectedPhoto(assetId = id)); content.file.delete()
                } else onPicked(SelectedPhoto(content = content))
                preparedPath?.takeIf { it != content.file.path }?.let { File(it).delete() }
                onDismiss()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { content.file.delete(); error = R.string.failed }
            finally { saving = false }
        }
    }
    if (preparedPath != null) {
        PhotoCropEditor(PreparedMedia(File(preparedPath!!), "image/jpeg", width, height), processor,
            onCancel = { if (!saving) { File(preparedPath!!).delete(); onDismiss() } },
            onConfirm = { if (!saving) finish(it) })
    } else if (pickingAsset) {
        AssetPicker(repository, onDismiss = { pickingAsset = false }, onPick = {
            onPicked(SelectedPhoto(assetId = it)); onDismiss()
        })
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.source_title), style = MaterialTheme.typography.titleLarge)
                if (uri == null) {
                    FilledTonalButton(onClick = {
                        try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        catch (_: Exception) { error = R.string.failed }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.photos)) }
                    FilledTonalButton(onClick = {
                        try {
                            val file = processor.cameraFile(); cameraPath = file.path; galleryHandled = false
                            capture.launch(processor.cameraUri(file))
                        } catch (_: Exception) { cameraPath?.let { File(it).delete() }; cameraPath = null; error = R.string.failed }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.camera)) }
                    if (!independent) FilledTonalButton(onClick = { pickingAsset = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.media_library)) }
                } else CircularProgressIndicator()
            }
        }
    }
    if (saving) Dialog(onDismissRequest = {}) { Surface(shape = MaterialTheme.shapes.large) { CircularProgressIndicator(Modifier.padding(24.dp)) } }
    error?.let { message ->
        AlertDialog(onDismissRequest = { error = null }, text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.confirm)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetPicker(repository: MediaRepository, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val assets by repository.assets.collectAsStateWithLifecycle(emptyList())
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(stringResource(R.string.media_library)) }, navigationIcon = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.back)) }
            })
        }) { padding ->
            if (assets.isEmpty()) Text(stringResource(R.string.empty_library), Modifier.padding(padding).padding(24.dp))
            LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(assets, key = { it.id }) { asset ->
                    Card(Modifier.clickable { onPick(asset.id) }) {
                        MediaImage(MediaLocator(asset.id), repository, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(asset.title ?: stringResource(R.string.untitled), Modifier.padding(8.dp), maxLines = 2)
                    }
                }
            }
        }
    }
}
