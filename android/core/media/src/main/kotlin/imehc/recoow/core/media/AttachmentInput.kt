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
import imehc.recoow.core.designsystem.*

/** UDF 草稿编辑器；保存时由属主 ViewModel 带着属主变更调用 saveAttachments。
 * 移除草稿不会删除共享资源，也不会改动已持久化的属主。 */
@Composable
fun AttachmentInput(attachments: List<MediaAttachment>, onChange: (List<MediaAttachment>) -> Unit,
    repository: MediaRepository, processor: ImageProcessor, preferences: AppPreferences, modifier: Modifier = Modifier,
    sectioned: Boolean = false, enabled: Boolean = true, onRecordingChange: (Boolean) -> Unit = {}, allowAudio: Boolean = true) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var recording by rememberSaveable { mutableStateOf(false) }
    val photos = attachments.filter { it.kind == AttachmentKind.PHOTO }
    val onRecorded: (PreparedMedia) -> Unit = { content ->
        recording = false
        onChange(attachments + MediaAttachment(kind = AttachmentKind.AUDIO, mimeType = content.mimeType,
            durationSeconds = content.durationSeconds, content = content))
    }
    val onPhotoPicked: (SelectedPhoto) -> Unit = { selected ->
        onChange(attachments + MediaAttachment(kind = AttachmentKind.PHOTO, assetId = selected.assetId,
            mimeType = selected.content?.mimeType ?: "image/jpeg", width = selected.content?.width,
            height = selected.content?.height, content = selected.content))
    }
    if (sectioned) AttachmentSections(attachments, onChange, repository, modifier, enabled, allowAudio,
        onPhoto = { picking = true }, onPreview = { previewId = it },
        onRecorded = onRecorded, onRecordingChange = onRecordingChange)
    else Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.attachments), style = MaterialTheme.typography.titleMedium)
        AttachmentPhotoGrid(photos, repository, onPreview = { previewId = it.id }, enabled = enabled,
            onRemove = { photo -> onChange(attachments.filterNot { it.id == photo.id }) },
            onMove = { id, target ->
                val from = photos.indexOfFirst { it.id == id }
                val to = photos.indexOfFirst { it.id == target }
                if (from >= 0 && to >= 0) onChange(attachments.movePhoto(id, to - from))
            })
        attachments.filter { allowAudio && it.kind == AttachmentKind.AUDIO }.forEach { attachment ->
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
                if (allowAudio) DropdownMenuItem(text = { Text(stringResource(R.string.record)) }, onClick = { menu = false; recording = true })
            }
        }
    }
    if (recording && allowAudio) AlertDialog(onDismissRequest = { recording = false }, text = {
        AudioRecorderInput(onRecorded = onRecorded)
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { recording = false }) { Text(stringResource(R.string.cancel)) } })
    if (picking) PhotoSourcePicker(processor, repository, preferences, onDismiss = { picking = false },
        onPicked = onPhotoPicked)
    previewId?.let { id ->
        val index = photos.indexOfFirst { it.id == id }
        if (index >= 0) ImagePreview(photos.map { it.locator }, index, repository) { previewId = null }
    }
}

/** 图片网格和录音各自成组，继续共用原有选择、预览、排序、播放与录制流程。 */
@Composable private fun AttachmentSections(attachments: List<MediaAttachment>, onChange: (List<MediaAttachment>) -> Unit,
    repository: MediaRepository, modifier: Modifier, enabled: Boolean, allowAudio: Boolean, onPhoto: () -> Unit,
    onPreview: (String) -> Unit, onRecorded: (PreparedMedia) -> Unit, onRecordingChange: (Boolean) -> Unit) {
    val photos = attachments.filter { it.kind == AttachmentKind.PHOTO }
    val audio = attachments.filter { it.kind == AttachmentKind.AUDIO }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
        Column {
            PhotoSectionHeader(repository, photos.mapNotNull { it.assetId })
            RecoowGroup {
                AttachmentPhotoGrid(photos, repository, onPreview = { onPreview(it.id) }, enabled = enabled,
                    onAdd = onPhoto, onRemove = { photo -> onChange(attachments.filterNot { it.id == photo.id }) },
                    onMove = { id, target ->
                        val from = photos.indexOfFirst { it.id == id }
                        val to = photos.indexOfFirst { it.id == target }
                        if (from >= 0 && to >= 0) onChange(attachments.movePhoto(id, to - from))
                    })
            }
        }
        if (allowAudio) Column {
            RecoowSectionHeader(stringResource(R.string.record))
            RecoowGroup {
                AudioRecorderInput(inline = true, enabled = enabled, onRecorded = onRecorded, onRecordingChange = onRecordingChange)
                audio.forEach { attachment ->
                    key(attachment.id) {
                        RecoowDivider(inset = RecoowLayout.rowHorizontal)
                        AttachmentAudioRow(attachment, repository, enabled = enabled,
                            onRemove = { onChange(attachments.filterNot { it.id == attachment.id }) })
                    }
                }
            }
        }
    }
}
