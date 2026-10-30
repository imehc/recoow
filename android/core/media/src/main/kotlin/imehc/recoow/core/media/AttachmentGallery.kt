package imehc.recoow.core.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.domain.media.*

@Composable fun AttachmentGallery(attachments: List<MediaAttachment>, repository: MediaRepository) {
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = attachments.filter { it.kind == AttachmentKind.PHOTO }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (attachments.isNotEmpty()) Text(stringResource(R.string.attachments), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { photo -> key(photo.id) { Column(Modifier.width(144.dp)) {
                MediaImage(photo.locator, repository, Modifier.fillMaxWidth().aspectRatio(1f).clickable { preview = photo.id })
                photo.title?.let { Text(it) }
            } } }
        }
        attachments.filter { it.kind == AttachmentKind.AUDIO }.forEach { audio -> key(audio.id) {
            Text(audio.title ?: stringResource(R.string.audio)); AudioPlayback(audio.locator, repository, audio.durationSeconds)
        } }
    }
    preview?.let { id -> val index = photos.indexOfFirst { it.id == id }
        if (index >= 0) ImagePreview(photos.map { it.locator }, index, repository) { preview = null }
    }
}
