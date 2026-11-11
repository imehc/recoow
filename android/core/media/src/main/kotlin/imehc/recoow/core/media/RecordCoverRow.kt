package imehc.recoow.core.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.RecoowPalette
import imehc.recoow.core.designsystem.RecordThumbnailRow
import imehc.recoow.core.domain.media.MediaAttachment
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.domain.media.MediaRepository

/** 日记与饮食列表共用封面槽位：首张图片优先，无图片或无法载入时显示功能图标。 */
@Composable
fun RecordCoverRow(photo: MediaAttachment?, repository: MediaRepository, icon: Int, tint: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    RecordCoverRow(locator = photo?.locator, repository = repository, icon = icon, tint = tint,
        onClick = onClick, modifier = modifier, description = photo?.title, content = content)
}

/** 单图账单与多附件记录共用布局，不改变各自的媒体存储模型。 */
@Composable
fun RecordCoverRow(locator: MediaLocator?, repository: MediaRepository, icon: Int, tint: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null,
    iconContent: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true, onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    RecordThumbnailRow(onClick, modifier, thumbnail = {
        val fallback: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize().background(tint), contentAlignment = Alignment.Center) {
                if (iconContent != null) iconContent() else Icon(painterResource(icon), null,
                    tint = if (tint.luminance() > 0.3f) RecoowPalette.onSurface else Color.White, modifier = Modifier.size(RecoowLayout.iconSize))
            }
        }
        Box(Modifier.size(RecoowLayout.listCoverSize).clip(RoundedCornerShape(RecoowLayout.thumbnailRadius))) {
            if (locator != null) MediaImage(locator, repository, Modifier.fillMaxSize(), description = description, fallback = fallback)
            else fallback()
        }
    }, trailing = trailing, enabled = enabled, onLongClick = onLongClick, content = content)
}
