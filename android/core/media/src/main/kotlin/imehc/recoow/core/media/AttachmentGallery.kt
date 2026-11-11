package imehc.recoow.core.media

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.RecoowSectionHeader
import imehc.recoow.core.domain.media.*

@Composable fun AttachmentGallery(attachments: List<MediaAttachment>, repository: MediaRepository,
    title: String = stringResource(R.string.attachment_photos)) {
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    val photos = attachments.filter { it.kind == AttachmentKind.PHOTO }
    val audio = attachments.filter { it.kind == AttachmentKind.AUDIO }
    if (attachments.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
        if (photos.isNotEmpty()) Column {
            PhotoSectionHeader(repository, photos.mapNotNull { it.assetId }, title)
            AttachmentPhotoGrid(photos, repository, onPreview = { preview = it.id },
                contentPadding = PaddingValues(horizontal = RecoowLayout.rowHorizontal))
        }
        if (audio.isNotEmpty()) Column {
            RecoowSectionHeader(stringResource(R.string.record))
            audio.forEach { attachment -> key(attachment.id) {
                AttachmentAudioRow(attachment, repository)
            } }
        }
    }
    preview?.let { id -> val index = photos.indexOfFirst { it.id == id }
        if (index >= 0) ImagePreview(photos.map { it.locator }, index, repository) { preview = null }
    }
}
