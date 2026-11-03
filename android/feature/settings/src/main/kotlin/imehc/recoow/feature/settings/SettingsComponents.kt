package imehc.recoow.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.RecoowGroup
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.RecoowSectionHeader

/**
 * 设置子页的分组标题 + 容器。子页不自己定间距，组与组之间由 [RecoowPage] 统一给 22dp。
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
fun SettingsProgress(text: String? = null) {
    RecoowGroup {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
