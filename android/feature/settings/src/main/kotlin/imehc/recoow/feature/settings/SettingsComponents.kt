package imehc.recoow.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.RecoowGroup
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.RecoowSectionHeader

/** 与表单小标题同起点的说明，不再为每句提示增加一张卡片。 */
@Composable
fun SettingsFooter(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(text, modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * 设置子页的分组标题 + 容器。子页通过 [RecoowPage] 的紧凑组间距与功能页保持一致。
 */
@Composable
fun SettingsSection(
    title: String,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        RecoowSectionHeader(title, trailing = trailing, onTrailingClick = onTrailingClick)
        content()
    }
}

/** 子页的主操作：46dp 高、12dp 圆角，与功能页一致。 */
@Composable
fun SettingsPrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 46.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

/**
 * 结果与说明条：与分组卡片同一种容器，不用另一套灰底横幅。
 * 需要动作时把动作放正文下方，点击区不小于 44dp。
 */
@Composable
fun SettingsBanner(
    text: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    RecoowGroup(modifier) {
        Column(
            Modifier.padding(horizontal = RecoowLayout.rowHorizontal, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (actionLabel != null && onAction != null) {
                Text(
                    actionLabel,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clickable(onClick = onAction)
                        .padding(vertical = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 进行中的任务：细进度条 + 一句说明，占用高度不超过两行。 */
@Composable
fun SettingsProgress(text: String? = null, progress: Float? = null, actionLabel: String? = null,
    onAction: (() -> Unit)? = null, actionTag: String? = null) {
    RecoowGroup {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            if (text != null || (actionLabel != null && onAction != null)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (text != null) Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (actionLabel != null && onAction != null) TextButton(onClick = onAction,
                        modifier = actionTag?.let { Modifier.testTag(it) } ?: Modifier) {
                        Text(actionLabel, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

/** 安全字段也复用项目的行式表单；凭据只保存在页面的临时状态中。 */
@Composable
internal fun SettingsInput(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true,
    secret: Boolean = false, keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    error: String? = null, hint: String? = null, tag: String = "") {
    imehc.recoow.core.designsystem.RecordFormText(label, value, onChange, enabled = enabled, placeholder = "", error = error,
        keyboard = if (secret) androidx.compose.ui.text.input.KeyboardType.Password else keyboard, tag = tag,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
    if (hint != null && error == null) Text(hint, Modifier.padding(horizontal = RecoowLayout.rowHorizontal).padding(bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
