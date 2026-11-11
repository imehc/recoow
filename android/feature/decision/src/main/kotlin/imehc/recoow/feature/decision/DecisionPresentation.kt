package imehc.recoow.feature.decision

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.decision.*
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.core.media.RecordCoverRow
import imehc.recoow.core.media.RecordPhotoSection
import imehc.recoow.core.media.ImagePreview

@Composable internal fun DecisionOptionRow(vm: DecisionViewModel, option: DecisionOption, enabled: Boolean,
    onBrowse: () -> Unit, onInspect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, selected: Boolean = false) {
    val tint = RecoowToolIcons.tint(ToolRoute.DECISION)
    RecordCoverRow(locator = if (option.hasImage) option.image else null, repository = vm.media,
        icon = CommonR.drawable.ic_tool_decision, tint = tint.copy(alpha = 0.12f),
        iconContent = { Icon(painterResource(CommonR.drawable.ic_tool_decision), null, tint = tint, modifier = Modifier.size(RecoowLayout.iconSize)) },
        onClick = { if (enabled) onBrowse() }, modifier = Modifier.testTag("option_${option.id}")
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else MaterialTheme.colorScheme.surface)
            .semantics { this.selected = selected }, trailing = {
            RecoowOverflowMenu(listOf(
                RecoowAction(stringResource(R.string.decision_option_details), enabled = enabled, onClick = onInspect),
                RecoowAction(stringResource(CommonR.string.record_edit), enabled = enabled, onClick = onEdit),
                RecoowAction(stringResource(CommonR.string.record_delete), enabled = enabled, onClick = onDelete),
            ))
        }) {
        Text(option.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RecordBadge(stringResource(R.string.decision_weight_value, option.weight))
            if (!option.enabled) RecordBadge(stringResource(R.string.decision_disabled), containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun DecisionChoiceRow(vm: DecisionViewModel, record: DecisionChoice, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = RecoowToolIcons.tint(ToolRoute.DECISION)
    RecordCoverRow(locator = if (record.hasImage) record.image else null, repository = vm.media,
        icon = CommonR.drawable.ic_tool_decision, tint = tint.copy(alpha = 0.12f),
        iconContent = { Icon(painterResource(CommonR.drawable.ic_tool_decision), null, tint = tint, modifier = Modifier.size(RecoowLayout.iconSize)) },
        onClick = { if (enabled) onClick() }, trailing = {
            Box(Modifier.size(RecoowLayout.controlSize), contentAlignment = Alignment.Center) { RecoowChevron() }
        }) {
        Text(record.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(recordDateTime(record.selectedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RecordBadge(record.collectionTitle)
    }
}

@Composable internal fun DecisionTextSection(title: String, text: String) {
    Column {
        RecoowSectionHeader(title)
        RecoowGroup {
            SelectionContainer { Text(text, Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DecisionCandidateSheet(vm: DecisionViewModel, option: DecisionOption, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var preview by rememberSaveable { mutableStateOf(false) }
    RecoowBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = RecoowLayout.pagePadding).padding(bottom = RecoowLayout.pagePadding),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap)) {
            RecoowSheetHeader(option.title, actions = {
                RecoowOverflowMenu(listOf(
                    RecoowAction(stringResource(CommonR.string.record_edit), onClick = onEdit),
                    RecoowAction(stringResource(CommonR.string.record_delete), onClick = onDelete),
                ))
            })
            RecoowGroup {
                RecoowTextValueRow(stringResource(R.string.decision_weight_label), option.weight.toString())
                RecoowDivider()
                RecoowTextValueRow(stringResource(R.string.decision_enabled), stringResource(if (option.enabled) R.string.decision_enabled_value else R.string.decision_disabled))
            }
            option.detail?.takeIf { it.isNotBlank() }?.let { DecisionTextSection(stringResource(R.string.decision_description), it) }
            option.customInfo?.takeIf { it.isNotBlank() }?.let { DecisionTextSection(stringResource(R.string.decision_custom), it) }
            if (option.hasImage) RecordPhotoSection(option.image, vm.media) { preview = true }
        }
    }
    if (preview) ImagePreview(listOf(option.image), 0, vm.media) { preview = false }
}
