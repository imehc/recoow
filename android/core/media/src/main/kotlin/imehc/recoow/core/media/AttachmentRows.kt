package imehc.recoow.core.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.domain.media.MediaAttachment
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.domain.media.MediaRepository
import imehc.recoow.core.designsystem.R as CommonR

/** 整张缩略图用于预览，移除叉号不带背景，查看与编辑共用同一组件。 */
@Composable
fun AttachmentPhotoTile(photo: MediaAttachment, repository: MediaRepository, onPreview: () -> Unit,
    modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null, enabled: Boolean = true) {
    RecordPhotoTile(photo.locator, repository, onPreview, modifier, description = photo.title,
        onRemove = onRemove, enabled = enabled, removeTag = "attachment_photo_remove_${photo.id}")
}

/** 附件与单图凭证共用缩略图、预览和无背景移除按钮。 */
@Composable
fun RecordPhotoTile(locator: MediaLocator, repository: MediaRepository, onPreview: () -> Unit,
    modifier: Modifier = Modifier, description: String? = null, onRemove: (() -> Unit)? = null,
    enabled: Boolean = true, removeTag: String = "record_photo_remove") {
    Surface(modifier, shape = RoundedCornerShape(RecoowLayout.thumbnailRadius), color = MaterialTheme.colorScheme.surfaceContainer) {
        Box {
            MediaImage(locator, repository, Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onPreview),
                description = description ?: stringResource(R.string.preview))
            if (onRemove != null) IconButton(onClick = onRemove, enabled = enabled,
                modifier = Modifier.align(Alignment.TopEnd).testTag(removeTag)) {
                Icon(painterResource(R.drawable.ic_photo_remove), stringResource(R.string.remove), tint = Color.Unspecified, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 单张图片详情共用小标题、素材名称和完整预览入口。 */
@Composable
fun RecordPhotoSection(locator: MediaLocator, repository: MediaRepository, onPreview: () -> Unit) {
    Column {
        PhotoSectionHeader(repository, listOfNotNull(locator.assetId))
        RecoowGroup {
            Box(Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal)) {
                RecordPhotoTile(locator, repository, onPreview, Modifier.size(RecoowLayout.photoInputSize))
            }
        }
    }
}

/** 录音附件复用单行播放器，唯一的移除操作直接显示，与新增操作共用点击区域。 */
@Composable
fun AttachmentAudioRow(audio: MediaAttachment, repository: MediaRepository, modifier: Modifier = Modifier,
    enabled: Boolean = true, onRemove: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal)) {
        audio.title?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(start = RecoowLayout.controlSize, top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AudioPlayback(audio.locator, repository, audio.durationSeconds, compact = true,
            trailing = {
                if (onRemove != null) IconButton(onClick = onRemove, enabled = enabled,
                    modifier = Modifier.size(RecoowLayout.controlSize).testTag("attachment_audio_remove_${audio.id}")) {
                    Icon(painterResource(CommonR.drawable.ic_close), stringResource(R.string.remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            })
    }
}
