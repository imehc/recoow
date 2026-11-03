package imehc.recoow.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.RecoowDivider
import imehc.recoow.core.designsystem.RecoowGroup
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.RecoowNotice
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.designsystem.RecoowRow
import imehc.recoow.core.designsystem.R as DesignR
import imehc.recoow.core.domain.update.AppUpdateProblem

@Composable
fun AppUpdateScreen(viewModel: AppUpdateViewModel, onBack: () -> Unit, onInstall: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.checkOnce() }
    RecoowPage(stringResource(R.string.update_title), navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.testTag("update_back")) {
            Icon(painterResource(DesignR.drawable.ic_record_back), stringResource(R.string.sync_back))
        }
    }, action = {
        TextButton(onClick = viewModel::check, enabled = !state.busy && state.progress == null,
            modifier = Modifier.testTag("update_check")) {
            Text(stringResource(R.string.update_check))
        }
    }) {
        val release = state.release
        item("installed") {
            SettingsSection(stringResource(R.string.update_section_current)) {
                RecoowGroup {
                    RecoowRow(
                        state.installed.name,
                        modifier = Modifier.testTag("update_installed"),
                    )
                }
            }
        }
        if (release != null) {
            item("release") {
                SettingsSection(stringResource(R.string.update_section_available), trailing = release.versionName) {
                    RecoowGroup {
                        RecoowRow(stringResource(R.string.update_size,
                            java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
                                .format(release.sizeBytes.toDouble() / (1024 * 1024))))
                        if (release.notes.isNotBlank()) {
                            RecoowDivider()
                            Column(
                                Modifier.padding(horizontal = RecoowLayout.rowHorizontal, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(stringResource(R.string.update_notes),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ReleaseNotes(release.notes)
                            }
                        }
                    }
                }
            }
            if (state.prepared != null) {
                item("ready") { SettingsBanner(stringResource(R.string.update_ready)) }
                item("install") {
                    SettingsPrimaryButton(
                        stringResource(R.string.update_install),
                        enabled = !state.busy,
                        modifier = Modifier.testTag("update_install"),
                        onClick = onInstall,
                    )
                }
            } else if (state.progress == null && !state.busy) {
                item("download") {
                    SettingsPrimaryButton(
                        stringResource(R.string.update_download),
                        modifier = Modifier.testTag("update_download"),
                        onClick = viewModel::download,
                    )
                }
            }
        }
        if (state.progress != null) {
            item("progress") {
                RecoowGroup {
                    Column(
                        Modifier.padding(horizontal = RecoowLayout.rowHorizontal, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.update_progress, ((state.progress ?: 0f) * 100).toInt()),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.update_keep_open),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.cancel),
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .clickable(onClick = viewModel::cancelDownload)
                                .padding(vertical = 10.dp)
                                .testTag("update_cancel"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else if (state.busy) {
            item("checking") { SettingsProgress(stringResource(R.string.update_checking)) }
        } else if (state.checked && release == null && state.problem == null) {
            item("current") {
                RecoowGroup {
                    RecoowRow(stringResource(R.string.update_current), modifier = Modifier.testTag("update_current"))
                }
            }
        }
        if (state.needsInstallPermission) item("permission") { RecoowNotice(stringResource(R.string.update_permission)) }
        state.problem?.let { problem ->
            item("problem") {
                SettingsBanner(
                    stringResource(when (problem) {
                        AppUpdateProblem.CONNECTION -> R.string.update_connection_failed
                        AppUpdateProblem.INVALID_RELEASE -> R.string.update_invalid
                        AppUpdateProblem.INCOMPATIBLE -> R.string.update_incompatible
                        AppUpdateProblem.STORAGE -> R.string.update_storage_failed
                        AppUpdateProblem.INSTALLER -> R.string.update_installer_failed
                    }),
                    error = true,
                    actionLabel = stringResource(R.string.update_check),
                    onAction = viewModel::check,
                )
            }
        }
    }
}

/**
 * 更新说明按发布页的轻量 Markdown 渲染：`#` 起行当小节标题、`-` 当项目符号，
 * 其余按正文。不引入完整 Markdown 渲染器，也不把 `##` 原样显示给用户。
 */
@Composable
private fun ReleaseNotes(text: String) {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        lines.forEachIndexed { index, line ->
            when {
                line.startsWith("#") -> Text(
                    line.trimStart('#').trim(),
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 9.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                line.startsWith("- ") || line.startsWith("* ") -> Row {
                    Text("·", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        line.drop(2).trim(),
                        modifier = Modifier.padding(start = 7.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                else -> Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
