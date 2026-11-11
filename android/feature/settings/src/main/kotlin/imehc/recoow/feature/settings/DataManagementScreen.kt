package imehc.recoow.feature.settings

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import imehc.recoow.core.designsystem.RecoowPalette
import imehc.recoow.core.designsystem.RecordSkeleton
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
fun DataManagementScreen(viewModel: DataManagementViewModel, onBack: () -> Unit,
    onMedia: () -> Unit = {}, onPhotos: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.preview { requireNotNull(context.contentResolver.openInputStream(uri)) }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.export { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) }
    }
    DataManagementContent(state, onBack, onMedia, onPhotos,
        onExport = { exporter.launch("Recoow-${java.time.LocalDate.now()}.recoowbackup") },
        onPickBackup = { picker.launch(arrayOf("*/*")) }, onDismissPreview = viewModel::dismissPreview,
        onRefresh = viewModel::refresh, onImport = viewModel::import, onOptimize = viewModel::optimize, onReset = viewModel::reset)
}

@Composable
fun DataManagementContent(state: DataManagementState, onBack: () -> Unit, onMedia: () -> Unit, onPhotos: () -> Unit,
    onExport: () -> Unit, onPickBackup: () -> Unit, onDismissPreview: () -> Unit, onRefresh: () -> Unit,
    onImport: (ImportMode, Set<ImportScope>, Boolean) -> Unit, onOptimize: () -> Unit, onReset: () -> Unit) {
    var allowUnverifiedAccount by remember(state.preview) { mutableStateOf(false) }
    val accountBlocked = state.preview?.accountStatus == BackupAccountStatus.MISMATCH || state.preview?.accountStatus == BackupAccountStatus.UNVERIFIED && !allowUnverifiedAccount
    val context = LocalContext.current
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf(ImportScope.entries.map { it.name }) }
    var storage by rememberSaveable { mutableStateOf(false) }
    val preview = state.preview
    val back = { if (!state.busy) { if (preview != null) onDismissPreview() else if (storage) storage = false else onBack() } }
    BackHandler(state.busy || preview != null || storage) { back() }
    val names = LocalResources.current.getStringArray(R.array.home_tool_names)
    fun size(value: Long) = Formatter.formatFileSize(context, value)
    fun date(value: Long?) = value?.let { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) }

    RecoowPage(
        stringResource(if (preview != null) R.string.backup_preview else if (storage) R.string.settings_storage_maintenance else R.string.data_title),
        navigationIcon = {
            IconButton(
                onClick = back,
                enabled = !state.busy,
            ) {
                Icon(painterResource(DesignR.drawable.ic_record_back), stringResource(R.string.sync_back))
            }
        }, contentSpacing = RecoowLayout.compactSectionGap,
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
                    onAction = onRefresh,
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
                ) { onImport(ImportMode.MERGE_MISSING, selected.map(ImportScope::valueOf).toSet(), allowUnverifiedAccount) }
            }
            item("replace") {
                RecoowGroup {
                    RecoowRow(
                        stringResource(R.string.backup_replace),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = if (!state.busy && !accountBlocked) ({ confirmation = "replace" }) else null,
                    )
                }
            }
        } else if (!storage) {
            item("backup") {
                SettingsSection(stringResource(R.string.data_section_backup)) {
                    RecoowGroup {
                        RecoowRow(
                            stringResource(R.string.backup_export),
                            onClick = if (!state.busy) onExport else null,
                            modifier = Modifier.testTag("backup_export"),
                        )
                        RecoowDivider()
                        RecoowRow(
                            stringResource(R.string.backup_import),
                            onClick = if (!state.busy) onPickBackup else null,
                            modifier = Modifier.testTag("backup_import"),
                        )
                    }
                }
            }
            state.usage?.takeIf { it.lastExport != null || it.lastImport != null }?.let { usage -> item("backup_dates") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    date(usage.lastExport)?.let { SettingsFooter(stringResource(R.string.backup_last_export, it)) }
                    date(usage.lastImport)?.let { SettingsFooter(stringResource(R.string.backup_last_import, it)) }
                }
            } }
            item("resources") { SettingsSection(stringResource(R.string.settings_photos_assets)) { RecoowGroup {
                SettingsNavigationRow(DesignR.drawable.ic_setting_media, stringResource(R.string.media_library), "media_settings",
                    onClick = { if (!state.busy) onMedia() })
                RecoowDivider(RecoowLayout.dividerInset)
                SettingsNavigationRow(DesignR.drawable.ic_photo, stringResource(R.string.photos_section), "photo_settings",
                    onClick = { if (!state.busy) onPhotos() })
            } } }
            item("storage_entry") { RecoowGroup {
                SettingsNavigationRow(DesignR.drawable.ic_setting_data, stringResource(R.string.settings_storage_maintenance), "storage_settings",
                    value = state.usage?.let { size(it.total) }, onClick = { if (!state.busy) storage = true })
            } }
        } else {
            state.usage?.let { usage -> item("storage") { SettingsSection(stringResource(R.string.storage_title), trailing = size(usage.total)) {
                RecoowGroup {
                    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
                        RecoowPalette.warningColor(MaterialTheme.colorScheme.background.luminance() < 0.5f), MaterialTheme.colorScheme.onSurfaceVariant)
                    val parts = listOf(usage.database, usage.media, usage.backups, usage.cache)
                    imehc.recoow.core.designsystem.RecordDistributionBar(parts.mapIndexed { index, bytes ->
                        imehc.recoow.core.designsystem.RecordChartSlice(if (usage.total > 0) bytes.toFloat() / usage.total else 0f, colors[index])
                    }, stringResource(R.string.storage_title), Modifier.padding(RecoowLayout.rowHorizontal))
                    listOf(R.string.storage_database, R.string.storage_media, R.string.storage_backups, R.string.storage_cache)
                        .forEachIndexed { index, label ->
                            RecoowRow(stringResource(label), trailingText = size(parts[index]), icon = {
                                Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(colors[index]))
                            })
                            if (index < 3) RecoowDivider(RecoowLayout.dividerInset)
                        }
                }
                SettingsFooter(stringResource(R.string.storage_embedded, size(usage.embedded)) + "\n" + stringResource(R.string.storage_schema, usage.schemaVersion),
                    Modifier.padding(top = 6.dp))
            } } } ?: item("storage_loading") { RecordSkeleton(rows = 4) }
            item("maintenance") {
                SettingsSection(stringResource(R.string.data_section_maintenance)) {
                    RecoowGroup {
                        RecoowRow(
                            stringResource(R.string.data_optimize),
                            subtitle = stringResource(R.string.data_optimize_detail),
                            onClick = if (!state.busy) onOptimize else null,
                            modifier = Modifier.testTag("data_optimize"),
                        )
                    }
                }
            }
            item("reset") {
                RecoowGroup {
                    RecoowRow(stringResource(R.string.data_reset), subtitle = stringResource(R.string.data_reset_detail),
                        titleColor = MaterialTheme.colorScheme.error, onClick = if (!state.busy) ({ confirmation = "reset" }) else null,
                        modifier = Modifier.testTag("data_reset"))
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
                TextButton(enabled = !state.busy && (action == "reset" || !accountBlocked), onClick = {
                    confirmation = null
                    if (action == "reset") onReset() else onImport(ImportMode.REPLACE_ALL, ImportScope.entries.toSet(), allowUnverifiedAccount)
                }) { Text(stringResource(if (action == "reset") R.string.data_reset else R.string.backup_replace), color = MaterialTheme.colorScheme.error) }
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
