package imehc.recoow.feature.settings

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.RecoowDivider
import imehc.recoow.core.designsystem.RecoowGroup
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.RecoowMetric
import imehc.recoow.core.designsystem.RecoowMetricGrid
import imehc.recoow.core.designsystem.RecoowNotice
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.designsystem.RecoowRow
import imehc.recoow.core.designsystem.R as DesignR
import imehc.recoow.core.domain.backup.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DataManagementScreen(viewModel: DataManagementViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var allowUnverifiedAccount by remember(state.preview) { mutableStateOf(false) }
    val accountBlocked = state.preview?.accountStatus == BackupAccountStatus.MISMATCH || state.preview?.accountStatus == BackupAccountStatus.UNVERIFIED && !allowUnverifiedAccount
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

    RecoowPage(
        stringResource(if (preview == null) R.string.data_title else R.string.backup_preview),
        navigationIcon = {
            IconButton(
                onClick = { if (preview != null) viewModel.dismissPreview() else onBack() },
                enabled = !state.busy,
            ) {
                Icon(painterResource(DesignR.drawable.ic_record_back), stringResource(R.string.sync_back))
            }
        },
    ) {
        if (state.busy) item("busy") { SettingsProgress(stringResource(R.string.data_working)) }

        state.result?.let { result ->
            item("result") {
                val error = result in setOf(DataResult.FAILED, DataResult.INVALID_BACKUP, DataResult.LEGACY_BACKUP, DataResult.ACCOUNT_MISMATCH, DataResult.ACCOUNT_UNVERIFIED, DataResult.LOCAL_CONFLICT)
                SettingsBanner(
                    when (result) {
                        DataResult.EXPORTED -> stringResource(R.string.backup_exported)
                        DataResult.IMPORTED -> stringResource(R.string.backup_imported, state.count)
                        DataResult.OPTIMIZED -> stringResource(R.string.data_optimized, size(state.count))
                        DataResult.RESET -> stringResource(R.string.data_reset_done)
                        DataResult.INVALID_BACKUP -> stringResource(R.string.backup_invalid)
                        DataResult.LEGACY_BACKUP -> stringResource(R.string.backup_legacy)
                        DataResult.ACCOUNT_MISMATCH -> stringResource(R.string.backup_account_mismatch)
                        DataResult.ACCOUNT_UNVERIFIED -> stringResource(R.string.backup_account_unknown)
                        DataResult.LOCAL_CONFLICT -> stringResource(R.string.backup_local_conflict, state.count)
                        DataResult.FAILED -> stringResource(R.string.data_failed)
                    },
                    error = error,
                    actionLabel = if (state.usage == null && !state.busy) stringResource(R.string.retry) else null,
                    onAction = viewModel::refresh,
                )
            }
        }

        if (preview != null) {
            val source = preview.preview
            item("source") {
                SettingsSection(stringResource(R.string.data_section_preview)) {
                    RecoowGroup {
                        RecoowRow(
                            stringResource(R.string.backup_source, source.sourcePlatform, source.appVersion, source.appBuild),
                            subtitle = date(source.exportedAt.toEpochMilli()).orEmpty(),
                        )
                        RecoowDivider()
                        RecoowRow(
                            stringResource(R.string.backup_summary, source.tableCounts.values.sum(), size(source.embeddedMediaBytes)),
                            subtitle = stringResource(R.string.backup_schema, source.formatVersion, source.databaseSchemaVersion),
                        )
                    }
                }
            }
            if (preview.accountStatus == BackupAccountStatus.MISMATCH) item("account") {
                SettingsBanner(stringResource(R.string.backup_account_mismatch), error = true)
            } else if (preview.accountStatus == BackupAccountStatus.UNVERIFIED) item("account") {
                RecoowGroup {
                    imehc.recoow.core.designsystem.RecoowSwitchRow(stringResource(R.string.backup_account_confirm), allowUnverifiedAccount, { allowUnverifiedAccount = it }, enabled = !state.busy)
                }
            }
            item("scope") {
                SettingsSection(
                    stringResource(R.string.data_section_scope),
                    trailing = "${selected.size}/${ImportScope.entries.size}",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecoowNotice(stringResource(R.string.backup_merge_detail))
                        RecoowGroup {
                            ImportScope.entries.forEachIndexed { index, scope ->
                                val checked = scope.name in selected
                                val toggle = {
                                    selected = if (checked) selected - scope.name else (selected + scope.name).distinct()
                                }
                                if (index > 0) RecoowDivider()
                                ScopeRow(
                                    title = names[index],
                                    subtitle = stringResource(R.string.backup_rows, scope.tables.sumOf { source.tableCounts[it] ?: 0 }),
                                    checked = checked,
                                    enabled = !state.busy,
                                    onToggle = toggle,
                                )
                            }
                        }
                    }
                }
            }
            item("merge") {
                SettingsPrimaryButton(
                    stringResource(R.string.backup_merge),
                    enabled = !state.busy && !accountBlocked && selected.isNotEmpty(),
                    modifier = Modifier.testTag("backup_merge"),
                ) { viewModel.import(ImportMode.MERGE_MISSING, selected.map(ImportScope::valueOf).toSet(), allowUnverifiedAccount) }
            }
            item("replace") {
                RecoowGroup {
                    RecoowRow(
                        stringResource(R.string.backup_replace),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { if (!accountBlocked) confirmation = "replace" },
                    )
                }
            }
        } else {
            item("backup") {
                SettingsSection(stringResource(R.string.data_section_backup)) {
                    RecoowGroup {
                        RecoowRow(
                            stringResource(R.string.backup_export),
                            subtitle = stringResource(R.string.backup_export_detail),
                            onClick = { exporter.launch("Recoow-${java.time.LocalDate.now()}.recoowbackup") },
                            modifier = Modifier.testTag("backup_export"),
                        )
                        RecoowDivider()
                        RecoowRow(
                            stringResource(R.string.backup_import),
                            subtitle = stringResource(R.string.backup_import_detail),
                            onClick = { picker.launch(arrayOf("*/*")) },
                            modifier = Modifier.testTag("backup_import"),
                        )
                    }
                }
            }
            state.usage?.let { usage ->
                item("storage") {
                    SettingsSection(stringResource(R.string.storage_title), trailing = size(usage.total)) {
                        RecoowGroup {
                            RecoowMetricGrid(
                                listOf(
                                    RecoowMetric(stringResource(R.string.storage_database), size(usage.database)),
                                    RecoowMetric(stringResource(R.string.storage_media), size(usage.media)),
                                    RecoowMetric(stringResource(R.string.storage_backups), size(usage.backups)),
                                    RecoowMetric(stringResource(R.string.storage_cache), size(usage.cache)),
                                ),
                            )
                            RecoowDivider()
                            Column(
                                Modifier.padding(horizontal = RecoowLayout.rowHorizontal, vertical = 11.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                listOfNotNull(
                                    stringResource(R.string.storage_embedded, size(usage.embedded)),
                                    stringResource(R.string.storage_schema, usage.schemaVersion),
                                    date(usage.lastExport)?.let { stringResource(R.string.backup_last_export, it) },
                                    date(usage.lastImport)?.let { stringResource(R.string.backup_last_import, it) },
                                ).forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            item("maintenance") {
                SettingsSection(stringResource(R.string.data_section_maintenance)) {
                    RecoowGroup {
                        RecoowRow(
                            stringResource(R.string.data_optimize),
                            subtitle = stringResource(R.string.data_optimize_detail),
                            onClick = viewModel::optimize,
                            modifier = Modifier.testTag("data_optimize"),
                        )
                        RecoowDivider()
                        RecoowRow(
                            stringResource(R.string.data_reset),
                            subtitle = stringResource(R.string.data_reset_detail),
                            titleColor = MaterialTheme.colorScheme.error,
                            onClick = { confirmation = "reset" },
                            modifier = Modifier.testTag("data_reset"),
                        )
                    }
                }
            }
        }
    }

    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(stringResource(if (action == "reset") R.string.data_reset else R.string.backup_replace)) },
            text = { Text(stringResource(if (action == "reset") R.string.data_reset_detail else R.string.backup_replace_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    if (action == "reset") viewModel.reset() else viewModel.import(ImportMode.REPLACE_ALL, ImportScope.entries.toSet(), allowUnverifiedAccount)
                }) { Text(stringResource(if (action == "reset") R.string.data_reset else R.string.backup_replace)) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 导入范围勾选行：整行可点，勾选框只是右侧状态指示。 */
@Composable
private fun ScopeRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = RecoowLayout.rowMinHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = RecoowLayout.rowHorizontal, vertical = RecoowLayout.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
