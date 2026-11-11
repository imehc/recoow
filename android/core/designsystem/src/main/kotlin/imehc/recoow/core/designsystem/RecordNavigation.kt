package imehc.recoow.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** 由记录跳转的导航入口提供；普通模块入口和外部深链保留逐级返回。 */
val LocalRecordReturnToSource = compositionLocalOf { false }

/** 编辑、选择等内部层级先关闭；入口详情返回发起跳转的页面。 */
@Composable
fun recordBackAction(
    busy: Boolean,
    nested: Boolean,
    entryDetail: Boolean,
    onNestedBack: () -> Unit,
    onBack: () -> Unit,
): () -> Unit {
    val returnToSource = LocalRecordReturnToSource.current
    return {
        if (!busy) {
            if (nested) onNestedBack()
            if (!nested || returnToSource && entryDetail) onBack()
        }
    }
}
