package imehc.recoow.core.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.domain.media.*
import imehc.recoow.core.domain.preferences.AppPreferences

/** UDF draft editor. The owner ViewModel calls saveAttachments with its owner change on Save.
 * Removing a draft never deletes a shared asset or mutates the persisted owner. */
@Composable
fun AttachmentInput(attachments: List<MediaAttachment>, onChange: (List<MediaAttachment>) -> Unit,
    repository: MediaRepository, processor: ImageProcessor, preferences: AppPreferences, modifier: Modifier = Modifier) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var recording by rememberSaveable { mutableStateOf(false) }
    val photos = attachments.filter { it.kind == AttachmentKind.PHOTO }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.attachments), style = MaterialTheme.typography.titleMedium)
        // Rows wrap naturally at large font sizes; three columns are a layout choice, never an input limit.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEachIndexed { index, attachment ->
                key(attachment.id) {
                    Card(Modifier.width(144.dp)) {
                        MediaImage(attachment.locator, repository, Modifier.fillMaxWidth().aspectRatio(1f).clickable { previewId = attachment.id })
                        Row {
                            TextButton(enabled = index > 0, onClick = { onChange(attachments.movePhoto(attachment.id, -1)) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.move_up)) }
                            TextButton(enabled = index < photos.lastIndex, onClick = { onChange(attachments.movePhoto(attachment.id, 1)) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.move_down)) }
                        }
                        TextButton(onClick = { onChange(attachments.filterNot { it.id == attachment.id }) }) { Text(stringResource(R.string.remove)) }
                    }
                }
            }
        }
        attachments.filter { it.kind == AttachmentKind.AUDIO }.forEach { attachment ->
            key(attachment.id) {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(attachment.title ?: stringResource(R.string.audio))
                        AudioPlayback(attachment.locator, repository, attachment.durationSeconds)
                        TextButton(onClick = { onChange(attachments.filterNot { it.id == attachment.id }) }) { Text(stringResource(R.string.remove)) }
                    }
                }
            }
        }
        // 一个入口收起所有添加动作（图片 / 录音），不再让按钮平铺在表单里。
        Box {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { menu = true }
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.add_attachment), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.add_photo)) }, onClick = { menu = false; picking = true })
                DropdownMenuItem(text = { Text(stringResource(R.string.record)) }, onClick = { menu = false; recording = true })
            }
        }
    }
    if (recording) AlertDialog(onDismissRequest = { recording = false }, text = {
        AudioRecorderInput { content ->
            recording = false
            onChange(attachments + MediaAttachment(kind = AttachmentKind.AUDIO, mimeType = content.mimeType,
                durationSeconds = content.durationSeconds, content = content))
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { recording = false }) { Text(stringResource(R.string.cancel)) } })
    if (picking) PhotoSourcePicker(processor, repository, preferences, onDismiss = { picking = false }, onPicked = { selected ->
        onChange(attachments + MediaAttachment(kind = AttachmentKind.PHOTO, assetId = selected.assetId,
            mimeType = selected.content?.mimeType ?: "image/jpeg", width = selected.content?.width,
            height = selected.content?.height, content = selected.content))
    })
    previewId?.let { id ->
        val index = photos.indexOfFirst { it.id == id }
        if (index >= 0) ImagePreview(photos.map { it.locator }, index, repository) { previewId = null }
    }
}
