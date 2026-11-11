package imehc.recoow.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 表单卡片：一行一个字段，行高 52dp、行间一条发丝线，组内不加额外间距。
 * 表单页用它把「一字段一个大控件」压成「一张卡里的几行」，屏幕小的时候尤其省地方。
 */
@Composable
fun RecordForm(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RecoowLayout.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(content = content) }
}

/** 表单小标题和字段卡片共用间距，避免各页叠加额外留白。 */
@Composable
fun RecordFormSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        RecoowSectionHeader(title)
        RecordForm(content = content)
    }
}

/** 两个字段共享分隔线，交换按钮叠在边界上，不给表单增加一行。 */
@Composable
fun RecordFormSwap(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier,
    first: @Composable () -> Unit, second: @Composable () -> Unit) {
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        Box { first() }
        RecordFormDivider()
        Box { second() }
        IconButton(onClick = onClick, enabled = enabled,
            modifier = modifier.size(RecoowLayout.controlSize).background(MaterialTheme.colorScheme.surface)) {
            Icon(painterResource(R.drawable.ic_swap_vertical), label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }) { children, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val firstRow = children[0].measure(loose)
        val divider = children[1].measure(loose)
        val secondRow = children[2].measure(loose)
        val button = children[3].measure(loose)
        val width = constraints.maxWidth
        val height = constraints.constrainHeight(firstRow.height + divider.height + secondRow.height)
        layout(width, height) {
            firstRow.placeRelative(0, 0)
            divider.placeRelative(0, firstRow.height)
            secondRow.placeRelative(0, firstRow.height + divider.height)
            button.placeRelative((width - button.width) / 2, firstRow.height + divider.height / 2 - button.height / 2)
        }
    }
}

/** 表单行之间的发丝线，与行内容左对齐。 */
@Composable
fun RecordFormDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = RecoowLayout.rowHorizontal),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 行式输入：标签在左、输入右对齐，回车即完成，不再给每个字段配一个描边输入框。
 * 字体缩放 ≥1.5 时改为上下排布，避免标签把输入挤没。
 */
@Composable
fun RecordFormText(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
    tag: String = "",
    showLabel: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val large = LocalDensity.current.fontScale >= 1.5f
    val labelColor = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val valueColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val field: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = fieldModifier.testTag(tag).then(if (showLabel) Modifier else Modifier.semantics { contentDescription = label }),
            enabled = enabled,
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyLarge)
                .copy(color = valueColor, textAlign = if (multiline) TextAlign.Start else TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                // 单行空值时给一个占位符号，否则一行只看得到标签，读不出这里可以填。
                // 多行区域本身就是一块空白输入区，再加占位反而多余。
                if (value.isEmpty() && !multiline) Text(
                    placeholder ?: "—",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                inner()
            },
        )
    }
    Column(modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal, vertical = 8.dp)) {
        if (large || multiline || !showLabel) {
            if (showLabel) Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor)
            field(Modifier.fillMaxWidth().padding(top = if (showLabel) 2.dp else 0.dp).heightIn(min = 28.dp))
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                field(Modifier.weight(1f))
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** 表单取值入口：与文本 / 选择字段共用字号，附加说明单独使用次级样式。 */
@Composable
fun RecordFormPickerRow(label: String, value: String?, onClick: () -> Unit, enabled: Boolean = true,
    supportingValue: String? = null, valueMaxLines: Int = 2, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = RecoowLayout.rowHorizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        Column(Modifier.weight(1f)) {
            Text(value ?: stringResource(R.string.record_choose), Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (value != null && enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End, maxLines = valueMaxLines, overflow = TextOverflow.Ellipsis)
            supportingValue?.let {
                Text(it, Modifier.fillMaxWidth().padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/** 行式日期时间：标签在左、日期与时间在右，点整行选择；不再用一个两行的整宽按钮。 */
@Composable
fun RecordFormDateTime(label: String, value: Long?, onChange: (Long) -> Unit, enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<Long?>(null) }
    val zone = ZoneId.systemDefault()
    val initial = Instant.ofEpochMilli(value ?: System.currentTimeMillis()).atZone(zone)
    RecordFormPickerRow(label, value?.let { recordDateTime(it) }, onClick = { open = true }, enabled = enabled)
    if (open) RecordDatePicker(initial.toLocalDate(), { open = false }) { pending = it.toEpochDay(); open = false }
    pending?.let { day ->
        val state = rememberTimePickerState(initial.hour, initial.minute, true)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(label) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimeInput(state) } },
            confirmButton = {
                TextButton(onClick = {
                    onChange(LocalDate.ofEpochDay(day).atTime(state.hour, state.minute).atZone(zone).toInstant().toEpochMilli())
                    pending = null
                }) { Text(stringResource(R.string.record_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.record_cancel)) } },
        )
    }
}

/** 只读行：标签在左、值在右，不加箭头，用于余额、统计这类不需要点的信息。 */
@Composable
fun RecordFormValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = RecoowLayout.rowHorizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 行式动作：整行可点（新增账户、去设置这类），值可省略。 */
@Composable
fun RecordFormAction(
    label: String,
    value: String? = null,
    enabled: Boolean = true,
    valueMaxLines: Int = 1,
    tag: String? = null,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick)
            .then(if (tag == null) Modifier else Modifier.testTag(tag))
            .padding(horizontal = RecoowLayout.rowHorizontal, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        value?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End, maxLines = valueMaxLines, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(16.dp))
    }
}

/** 表单里的说明小字：一句提醒，不占一整块卡片。 */
@Composable
fun RecordFormHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal, vertical = 8.dp))
}
