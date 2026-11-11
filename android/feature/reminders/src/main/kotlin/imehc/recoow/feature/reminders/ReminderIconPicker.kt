package imehc.recoow.feature.reminders

import android.icu.text.BreakIterator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.domain.reminders.reminderIcons

/** 与 iOS 一致：常用图标立即选择，自定义内容取第一个完整字符。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderIconPickerRow(selection: String, onSelect: (String) -> Unit, enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled) { open = true }
        .padding(horizontal = RecoowLayout.rowHorizontal, vertical = 8.dp).testTag("reminder_icon_picker"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.reminder_icon), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        ReminderMemoryTile(selection)
        RecoowChevron()
    }
    if (open) RecoowBottomSheet(onDismissRequest = { open = false }) {
        var custom by rememberSaveable { mutableStateOf(if (selection in reminderIcons) "" else selection) }
        val normalized = remember(custom) {
            val value = custom.trim()
            if (value.isEmpty()) "" else BreakIterator.getCharacterInstance().run {
                setText(value)
                first()
                value.substring(0, next())
            }
        }
        val choose: (String) -> Unit = { onSelect(it); open = false }
        val useCustom: () -> Unit = { if (normalized.isNotEmpty()) choose(normalized) }
        val iconNames = LocalResources.current.getStringArray(R.array.reminder_icon_names)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = RecoowLayout.pagePadding).padding(bottom = RecoowLayout.pagePadding),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
            RecoowSheetHeader(stringResource(R.string.reminder_icon_select))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                reminderIcons.forEachIndexed { index, icon ->
                    Surface(onClick = { choose(icon) }, shape = RoundedCornerShape(RecoowLayout.thumbnailRadius),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = if (selection == icon) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.size(52.dp).testTag("reminder_icon_$index").semantics {
                            contentDescription = iconNames[index]
                            selected = selection == icon
                        }) {
                        Box(contentAlignment = Alignment.Center) { ReminderMemoryGlyph(icon) }
                    }
                }
            }
            RecordForm {
                Row(Modifier.fillMaxWidth().padding(start = RecoowLayout.rowHorizontal, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.reminder_icon_custom), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(custom, { custom = it }, Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("reminder_custom_icon"), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { useCustom() }),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (custom.isEmpty()) Text(stringResource(R.string.reminder_icon_input),
                                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                inner()
                            }
                        })
                    TextButton(onClick = useCustom, enabled = normalized.isNotEmpty(), modifier = Modifier.testTag("reminder_use_custom_icon")) {
                        Text(stringResource(R.string.reminder_icon_use))
                    }
                }
            }
        }
    }
}
