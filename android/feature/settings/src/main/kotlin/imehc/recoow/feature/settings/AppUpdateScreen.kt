package imehc.recoow.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as DesignR
import imehc.recoow.core.domain.update.AppUpdateProblem

@Composable
fun AppUpdateScreen(viewModel: AppUpdateViewModel, onBack: () -> Unit, onInstall: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.checkOnce() }
    AppUpdateContent(state, onBack, viewModel::check, viewModel::download, viewModel::cancelDownload, onInstall)
}

@Composable
fun AppUpdateContent(state: AppUpdateUiState, onBack: () -> Unit, onCheck: () -> Unit,
    onDownload: () -> Unit, onCancelDownload: () -> Unit, onInstall: () -> Unit) {
    val context = LocalContext.current
    val available = !state.busy && state.progress == null
    RecoowPage(stringResource(R.string.update_title), navigationIcon = { SettingsBack(onBack, tag = "update_back") },
        action = {
            IconButton(onClick = onCheck, enabled = available, modifier = Modifier.testTag("update_check")) {
                Icon(painterResource(DesignR.drawable.ic_setting_sync), stringResource(R.string.update_check))
            }
        }, contentSpacing = RecoowLayout.compactSectionGap) {
        item("installed") {
            SettingsSection(stringResource(R.string.update_section_current)) {
                RecoowGroup(Modifier.testTag("update_installed")) {
                    RecoowTextValueRow(stringResource(R.string.update_version_label), state.installed.name)
                    if (state.checked && state.release == null && state.problem == null && available) {
                        RecoowDivider()
                        RecoowRow(stringResource(R.string.update_current), Modifier.testTag("update_current"), icon = {
                            Icon(painterResource(DesignR.drawable.ic_check), null,
                                tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(RecoowLayout.iconSize))
                        })
                    }
                }
            }
        }
        if (state.busy && state.progress == null) item("checking") {
            SettingsProgress(stringResource(R.string.update_checking))
        }
        state.release?.let { release ->
            item("release") {
                SettingsSection(stringResource(R.string.update_section_available)) {
                    RecoowGroup {
                        RecoowTextValueRow(stringResource(R.string.update_version_label), release.versionName)
                        RecoowDivider()
                        RecoowTextValueRow(stringResource(R.string.update_size_label), Formatter.formatFileSize(context, release.sizeBytes))
                    }
                }
            }
            if (state.progress != null) item("progress") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("update_progress")) {
                    SettingsProgress(stringResource(R.string.update_progress, (state.progress * 100).toInt()), state.progress,
                        actionLabel = stringResource(R.string.cancel), onAction = onCancelDownload, actionTag = "update_cancel")
                    SettingsFooter(stringResource(R.string.update_keep_open))
                }
            } else if (state.prepared != null) item("install") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsPrimaryButton(stringResource(R.string.update_install), enabled = !state.busy,
                        modifier = Modifier.testTag("update_install"), onClick = onInstall)
                    SettingsFooter(stringResource(R.string.update_ready))
                }
            } else if (available) item("download") {
                SettingsPrimaryButton(stringResource(R.string.update_download), modifier = Modifier.testTag("update_download"), onClick = onDownload)
            }
            if (release.notes.isNotBlank()) item("notes") {
                SettingsSection(stringResource(R.string.update_notes)) {
                    RecoowGroup(Modifier.testTag("update_notes")) {
                        Column(Modifier.fillMaxWidth().padding(RecoowLayout.rowHorizontal)) { ReleaseNotes(release.notes) }
                    }
                }
            }
        }
        if (state.needsInstallPermission) item("permission") {
            SettingsBanner(stringResource(R.string.update_permission), modifier = Modifier.testTag("update_permission"))
        }
        state.problem?.let { problem -> item("problem") {
            SettingsBanner(stringResource(when (problem) {
                AppUpdateProblem.CONNECTION -> R.string.update_connection_failed
                AppUpdateProblem.INVALID_RELEASE -> R.string.update_invalid
                AppUpdateProblem.INCOMPATIBLE -> R.string.update_incompatible
                AppUpdateProblem.STORAGE -> R.string.update_storage_failed
                AppUpdateProblem.INSTALLER -> R.string.update_installer_failed
            }), error = true, actionLabel = stringResource(R.string.update_check), onAction = if (available) onCheck else null)
        } }
    }
}

/** 更新说明沿用发布页的轻量 Markdown；只负责呈现，不改变更新协议。 */
@Composable
private fun ReleaseNotes(text: String) {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEachIndexed { index, line ->
            when {
                line.startsWith("#") -> Text(line.trimStart('#').trim(),
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                line.startsWith("- ") || line.startsWith("* ") -> Row {
                    Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(line.drop(2).trim(), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                else -> Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
