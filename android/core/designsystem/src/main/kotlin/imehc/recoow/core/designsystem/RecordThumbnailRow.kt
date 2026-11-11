package imehc.recoow.core.designsystem

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 图片与分类图标共用的记录行，固定封面尺寸并与正文垂直居中。 */
@Composable
fun RecordThumbnailRow(onClick: () -> Unit, modifier: Modifier = Modifier,
    thumbnail: @Composable () -> Unit, trailing: (@Composable () -> Unit)? = null,
    trailingAlignment: Alignment.Vertical = Alignment.CenterVertically,
    enabled: Boolean = true, onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxWidth().combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = RecoowLayout.rowHorizontal, vertical = RecoowLayout.rowHorizontal),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RecoowLayout.iconTextGap)) {
        Box(Modifier.size(RecoowLayout.listCoverSize), contentAlignment = Alignment.Center) { thumbnail() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
        trailing?.let { Box(Modifier.align(trailingAlignment)) { it() } }
    }
}
