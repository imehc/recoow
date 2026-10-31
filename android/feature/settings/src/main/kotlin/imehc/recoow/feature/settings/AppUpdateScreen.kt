package imehc.recoow.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.domain.update.AppUpdateProblem

@Composable
fun AppUpdateScreen(viewModel: AppUpdateViewModel, onBack: () -> Unit, onInstall: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.checkOnce() }
    RecoowPage(stringResource(R.string.update_title), navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.testTag("update_back")) {
            Icon(painterResource(R.drawable.ic_sync_back), stringResource(R.string.sync_back))
        }
    }) {
        item { Text(stringResource(R.string.update_installed, state.installed.name), Modifier.testTag("update_installed")) }
        val release = state.release
        if (release != null) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.update_available, release.versionName), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.update_size, java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
                        .format(release.sizeBytes.toDouble() / (1024 * 1024))))
                    if (release.notes.isNotBlank()) Text(release.notes)
                    if (state.prepared != null) {
                        Text(stringResource(R.string.update_ready))
                        Button(onClick = onInstall, enabled = !state.busy, modifier = Modifier.testTag("update_install")) {
                            Text(stringResource(R.string.update_install))
                        }
                    } else if (state.progress == null) Button(onClick = viewModel::download, enabled = !state.busy,
                        modifier = Modifier.testTag("update_download")) { Text(stringResource(R.string.update_download)) }
                }
            }
        }
        if (state.progress != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(progress = { state.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.update_progress, ((state.progress ?: 0f) * 100).toInt()))
                Text(stringResource(R.string.update_keep_open))
                TextButton(onClick = viewModel::cancelDownload, modifier = Modifier.testTag("update_cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            }
        } else if (state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.update_checking))
        } else if (state.checked && release == null && state.problem == null) item {
            Text(stringResource(R.string.update_current), Modifier.testTag("update_current"))
        }
        if (state.needsInstallPermission) item { Text(stringResource(R.string.update_permission)) }
        state.problem?.let { problem -> item {
            Text(stringResource(when (problem) {
                AppUpdateProblem.CONNECTION -> R.string.update_connection_failed
                AppUpdateProblem.INVALID_RELEASE -> R.string.update_invalid
                AppUpdateProblem.INCOMPATIBLE -> R.string.update_incompatible
                AppUpdateProblem.STORAGE -> R.string.update_storage_failed
                AppUpdateProblem.INSTALLER -> R.string.update_installer_failed
            }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("update_error"))
        } }
        item { TextButton(onClick = viewModel::check, enabled = !state.busy, modifier = Modifier.testTag("update_check")) {
            Text(stringResource(R.string.update_check))
        } }
    }
}
