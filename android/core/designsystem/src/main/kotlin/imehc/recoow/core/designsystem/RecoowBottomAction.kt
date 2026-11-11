package imehc.recoow.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 详情主操作固定在底部，保留系统安全区和内容滚动空间。 */
@Composable
fun RecoowBottomAction(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier,
    secondaryLabel: String? = null, onSecondaryClick: () -> Unit = {}, secondaryEnabled: Boolean = enabled) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(horizontal = RecoowLayout.pagePadding).padding(top = 8.dp, bottom = 12.dp)) {
            if (secondaryLabel != null) {
                OutlinedButton(onClick = onSecondaryClick, enabled = secondaryEnabled, modifier = Modifier.heightIn(min = 46.dp),
                    shape = RoundedCornerShape(12.dp)) { Text(secondaryLabel, style = MaterialTheme.typography.titleSmall) }
                Spacer(Modifier.width(12.dp))
            }
            Button(onClick = onClick, enabled = enabled, modifier = modifier.weight(1f).heightIn(min = 46.dp),
                shape = RoundedCornerShape(12.dp)) { Text(label, style = MaterialTheme.typography.titleSmall) }
        }
    }
}
