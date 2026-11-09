package imehc.recoow.core.designsystem

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 信息行内复制，供缴费号码、用户名等完整文本值共用。 */
@Composable
fun RecoowCopyableValueRow(title: String, value: String, copyLabel: String, copiedLabel: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(2_000); copied = false }
    }
    BoxWithConstraints {
        val valueWidth = maxWidth * 0.65f
        RecoowRow(title, trailing = {
            Row(Modifier.widthIn(max = valueWidth), verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f, fill = false)) {
                    Text(value, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(title, value)))
                        copied = true
                    }
                }) {
                    Icon(painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy),
                        contentDescription = if (copied) copiedLabel else copyLabel,
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        })
    }
}
