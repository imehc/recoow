package imehc.recoow.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 底部面板共用表面色、圆角和系统拖拽条；通过拖拽、遮罩和返回关闭。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoowBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        content = content,
    )
}

/** 标题和汇总值同行；仅保留保存、更多等实际操作，不额外添加关闭按钮。 */
@Composable
fun RecoowSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maximumValueWidth = maxWidth * .6f
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface)
            value?.let {
                Text(it, Modifier.widthIn(max = maximumValueWidth), style = MaterialTheme.typography.bodyLarge, color = valueColor)
            }
            if (actions != null) Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
