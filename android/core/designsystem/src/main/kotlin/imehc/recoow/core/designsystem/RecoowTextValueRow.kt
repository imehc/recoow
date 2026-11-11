package imehc.recoow.core.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/** 完整文本取值行：保留换行并按内容增长，供线路等多行详情复用。 */
@Composable
fun RecoowTextValueRow(title: String, value: String) {
    BoxWithConstraints {
        val valueWidth = maxWidth * 0.65f
        RecoowRow(title, trailing = {
            SelectionContainer(Modifier.widthIn(max = valueWidth)) {
                Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }
}
