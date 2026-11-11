package imehc.recoow.core.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.preferences.AppPreferences
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** 账单、提醒、物品与选项的单图编辑器；裁剪只生成一份本地替换副本，
 * 只有资源库中显式的「替换」命令才会改动共享资源。 */
@Composable
fun PhotoInput(locator: MediaLocator?, onChange: (SelectedPhoto?) -> Unit, repository: MediaRepository,
    processor: ImageProcessor, preferences: AppPreferences, modifier: Modifier = Modifier, compact: Boolean = false) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable(stateSaver = PreparedMediaSaver) { mutableStateOf<PreparedMedia?>(null) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val crop: () -> Unit = {
        if (locator != null) {
            loading = true
            scope.launch {
                try { editing = processor.prepare(android.net.Uri.fromFile(requireNotNull(repository.resolve(locator).file))) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { error = true }
                finally { loading = false }
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (compact) Column {
            PhotoSectionHeader(repository, listOfNotNull(locator?.assetId))
            RecoowGroup {
                val content = remember(locator, repository) { locator?.let { repository.observeContent(it).catch { emit(ResolvedMedia(MediaAvailability.DAMAGED)) } } }
                val resolved = content?.collectAsStateWithLifecycle(ResolvedMedia(MediaAvailability.PENDING))?.value
                Row(Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RecoowLayout.iconTextGap)) {
                    if (locator != null) RecordPhotoTile(locator, repository, { preview = true }, Modifier.size(RecoowLayout.photoInputSize),
                        description = resolved?.title, onRemove = { preview = false; onChange(null) }, enabled = !loading)
                    else Surface(Modifier.size(RecoowLayout.photoInputSize), shape = RoundedCornerShape(RecoowLayout.thumbnailRadius),
                        color = MaterialTheme.colorScheme.surfaceContainer) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(CommonR.drawable.ic_photo), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.weight(1f).heightIn(min = RecoowLayout.photoInputSize).clickable(enabled = !loading) { picking = true },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (locator?.assetId != null) resolved?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.untitled)
                                else stringResource(if (locator == null) R.string.select_photo else R.string.replace),
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(if (locator == null) R.string.photo_select_hint else R.string.photo_replace_hint),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RecoowChevron()
                    }
                    if (locator != null && locator.assetId == null) IconButton(onClick = crop, enabled = !loading && resolved?.availability == MediaAvailability.READY) {
                        Icon(painterResource(CommonR.drawable.ic_crop), stringResource(R.string.crop),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
        } else {
            if (locator != null) {
                val content = remember(locator, repository) { repository.observeContent(locator).catch { emit(ResolvedMedia(MediaAvailability.DAMAGED)) } }
                val resolved by content.collectAsStateWithLifecycle(ResolvedMedia(MediaAvailability.PENDING))
                MediaImage(locator, repository, Modifier.fillMaxWidth().height(200.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable { preview = true })
                resolved.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !loading && resolved.availability == MediaAvailability.READY, onClick = crop) { Text(stringResource(R.string.crop)) }
                    TextButton(onClick = { onChange(null) }) { Text(stringResource(R.string.remove)) }
                }
            }
            OutlinedButton(
                onClick = { picking = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(if (locator == null) R.string.add_photo else R.string.replace), style = MaterialTheme.typography.titleSmall) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error) Text(stringResource(R.string.failed), color = MaterialTheme.colorScheme.error)
    }
    if (picking) PhotoSourcePicker(processor, repository, preferences, { picking = false }, { onChange(it) })
    if (preview && locator != null) ImagePreview(listOf(locator), 0, repository) { preview = false }
    editing?.let { source -> PhotoCropEditor(source, processor,
        onCancel = { source.file.delete(); editing = null },
        onConfirm = { source.file.delete(); editing = null; onChange(SelectedPhoto(content = it)) }) }
}
