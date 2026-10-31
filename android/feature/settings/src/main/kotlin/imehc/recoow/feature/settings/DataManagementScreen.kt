package imehc.recoow.feature.settings

import imehc.recoow.core.designsystem.recordToggleState
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.domain.backup.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DataManagementScreen(viewModel: DataManagementViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.preview { requireNotNull(context.contentResolver.openInputStream(uri)) }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.export { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) }
    }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf(ImportScope.entries.map { it.name }) }
    val preview = state.preview
    BackHandler(state.busy || preview != null) { if (!state.busy) viewModel.dismissPreview() }
    val names = LocalResources.current.getStringArray(R.array.home_tool_names)
    fun size(value: Long) = Formatter.formatFileSize(context, value)
    fun date(value: Long?) = value?.let { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) }
    RecoowPage(stringResource(if (preview == null) R.string.data_title else R.string.backup_preview), navigationIcon = {
        TextButton(onClick = { if (preview != null) viewModel.dismissPreview() else onBack() }, enabled = !state.busy) { Text(stringResource(R.string.sync_back)) }
    }) {
        if (state.busy) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.data_working), style = MaterialTheme.typography.bodyMedium)
        } }
        state.result?.let { result -> item {
            val text = when (result) {
                DataResult.EXPORTED -> stringResource(R.string.backup_exported)
                DataResult.IMPORTED -> stringResource(R.string.backup_imported, state.count)
                DataResult.OPTIMIZED -> stringResource(R.string.data_optimized, size(state.count))
                DataResult.RESET -> stringResource(R.string.data_reset_done)
                DataResult.INVALID_BACKUP -> stringResource(R.string.backup_invalid)
                DataResult.LEGACY_BACKUP -> stringResource(R.string.backup_legacy)
                DataResult.FAILED -> stringResource(R.string.data_failed)
            }
            Text(text, color = if (result in setOf(DataResult.FAILED, DataResult.INVALID_BACKUP, DataResult.LEGACY_BACKUP)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (state.usage == null && !state.busy) TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
        } }
        if (preview != null) {
            item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_source, preview.preview.sourcePlatform, preview.preview.appVersion, preview.preview.appBuild), style = MaterialTheme.typography.titleMedium)
                Text(date(preview.preview.exportedAt.toEpochMilli()).orEmpty())
                Text(stringResource(R.string.backup_summary, preview.preview.tableCounts.values.sum(), size(preview.preview.embeddedMediaBytes)))
                Text(stringResource(R.string.backup_schema, preview.preview.formatVersion, preview.preview.databaseSchemaVersion), style = MaterialTheme.typography.bodySmall)
            } } }
            item { Text(stringResource(R.string.backup_merge_detail), style = MaterialTheme.typography.bodyMedium) }
            item { Card { ImportScope.entries.forEachIndexed { index, scope ->
                val checked = scope.name in selected
                val toggle = { value: Boolean -> selected = if (value) (selected + scope.name).distinct() else selected - scope.name }
                ListItem(onClick = { toggle(!checked) }, enabled = !state.busy, modifier = Modifier.recordToggleState(checked),
                    trailingContent = { Checkbox(checked, null, enabled = !state.busy) },
                    supportingContent = { Text(stringResource(R.string.backup_rows, scope.tables.sumOf { preview.preview.tableCounts[it] ?: 0 })) }) { Text(names[index]) }
            } } }
            item { Button(onClick = { viewModel.import(ImportMode.MERGE_MISSING, selected.map(ImportScope::valueOf).toSet()) }, enabled = !state.busy && selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("backup_merge")) { Text(stringResource(R.string.backup_merge)) } }
            item { OutlinedButton(onClick = { confirmation = "replace" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup_replace")) { Text(stringResource(R.string.backup_replace)) } }
        } else {
            item { Card {
                ListItem(onClick = { exporter.launch("Recoow-${java.time.LocalDate.now()}.recoowbackup") }, enabled = !state.busy,
                    supportingContent = { Text(stringResource(R.string.backup_export_detail)) }, modifier = Modifier.testTag("backup_export")) { Text(stringResource(R.string.backup_export)) }
                ListItem(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy,
                    supportingContent = { Text(stringResource(R.string.backup_import_detail)) }, modifier = Modifier.testTag("backup_import")) { Text(stringResource(R.string.backup_import)) }
            } }
            state.usage?.let { usage ->
                item { Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.titleSmall) }
                item { Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(size(usage.total), style = MaterialTheme.typography.headlineSmall)
                    listOf(R.string.storage_database to usage.database, R.string.storage_media to usage.media,
                        R.string.storage_backups to usage.backups, R.string.storage_cache to usage.cache).forEach { (label, bytes) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(stringResource(label), Modifier.weight(1f)); Text(size(bytes)) }
                    }
                    Text(stringResource(R.string.storage_embedded, size(usage.embedded)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.storage_schema, usage.schemaVersion), style = MaterialTheme.typography.bodySmall)
                    date(usage.lastExport)?.let { Text(stringResource(R.string.backup_last_export, it), style = MaterialTheme.typography.bodySmall) }
                    date(usage.lastImport)?.let { Text(stringResource(R.string.backup_last_import, it), style = MaterialTheme.typography.bodySmall) }
                } } }
            }
            item { Card {
                ListItem(onClick = viewModel::optimize, enabled = !state.busy, supportingContent = { Text(stringResource(R.string.data_optimize_detail)) }, modifier = Modifier.testTag("data_optimize")) { Text(stringResource(R.string.data_optimize)) }
                ListItem(onClick = { confirmation = "reset" }, enabled = !state.busy, modifier = Modifier.testTag("data_reset")) { Text(stringResource(R.string.data_reset), color = MaterialTheme.colorScheme.error) }
            } }
        }
    }
    confirmation?.let { action -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(stringResource(if (action == "reset") R.string.data_reset else R.string.backup_replace)) },
        text = { Text(stringResource(if (action == "reset") R.string.data_reset_detail else R.string.backup_replace_detail)) },
        confirmButton = { TextButton(onClick = {
            confirmation = null
            if (action == "reset") viewModel.reset() else viewModel.import(ImportMode.REPLACE_ALL, ImportScope.entries.toSet())
        }) { Text(stringResource(if (action == "reset") R.string.data_reset else R.string.backup_replace)) } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.cancel)) } }) }
}
