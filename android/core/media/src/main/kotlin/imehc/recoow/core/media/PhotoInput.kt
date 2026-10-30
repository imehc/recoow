package imehc.recoow.core.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Single-image editor for bills, reminders, items and choices. Cropping creates a local replacement;
 * only the asset library's explicit Replace command modifies a shared asset. */
@Composable
fun PhotoInput(locator: MediaLocator?, onChange: (SelectedPhoto?) -> Unit, repository: MediaRepository,
    processor: ImageProcessor, preferences: AppPreferences, modifier: Modifier = Modifier) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable(stateSaver = PreparedMediaSaver) { mutableStateOf<PreparedMedia?>(null) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (locator != null) {
            val content = remember(locator, repository) { repository.observeContent(locator).catch { emit(ResolvedMedia(MediaAvailability.DAMAGED)) } }
            val resolved by content.collectAsStateWithLifecycle(ResolvedMedia(MediaAvailability.PENDING))
            MediaImage(locator, repository, Modifier.fillMaxWidth().height(200.dp).clickable { preview = true })
            resolved.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !loading && resolved.availability == MediaAvailability.READY, onClick = {
                    loading = true
                    scope.launch {
                        try { editing = processor.prepare(android.net.Uri.fromFile(requireNotNull(repository.resolve(locator).file))) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
                        finally { loading = false }
                    }
                }) { Text(stringResource(R.string.crop)) }
                TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.remove)) }
            }
        }
        FilledTonalButton(onClick = { picking = true }) { Text(stringResource(if (locator == null) R.string.add_photo else R.string.replace)) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error) Text(stringResource(R.string.failed), color = MaterialTheme.colorScheme.error)
    }
    if (picking) PhotoSourcePicker(processor, repository, preferences, { picking = false }, { onChange(it) })
    if (preview && locator != null) ImagePreview(listOf(locator), 0, repository) { preview = false }
    editing?.let { source -> PhotoCropEditor(source, processor,
        onCancel = { source.file.delete(); editing = null },
        onConfirm = { source.file.delete(); editing = null; onChange(SelectedPhoto(content = it)) }) }
}
