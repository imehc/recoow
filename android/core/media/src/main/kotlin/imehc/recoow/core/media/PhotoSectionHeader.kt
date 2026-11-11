package imehc.recoow.core.media

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.RecoowSectionHeader
import imehc.recoow.core.domain.media.MediaRepository

/** 图片小标题右侧展示所选素材名称；选择来源统一由 PhotoSourcePicker 提供。 */
@Composable
fun PhotoSectionHeader(repository: MediaRepository, assetIds: List<String>,
    title: String = stringResource(R.string.attachment_photos)) {
    val assetTitle = if (assetIds.isEmpty()) null else {
        val assets by repository.assets.collectAsStateWithLifecycle(emptyList())
        val untitled = stringResource(R.string.untitled)
        remember(assetIds, assets, untitled) {
            val byId = assets.associateBy { it.id }
            assetIds.distinct().map { byId[it]?.title?.takeIf(String::isNotBlank) ?: untitled }.joinToString(" · ")
        }
    }
    BoxWithConstraints {
        RecoowSectionHeader(title, trailing = assetTitle, trailingModifier = Modifier.widthIn(max = maxWidth / 2))
    }
}
