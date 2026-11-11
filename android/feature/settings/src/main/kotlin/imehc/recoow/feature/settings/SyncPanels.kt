package imehc.recoow.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as DesignR
import imehc.recoow.core.domain.sync.*

@Composable internal fun syncStatusText(state: SyncState): String = stringResource(when {
    state.running -> R.string.account_sync_running
    state.problem == SyncProblem.AUTHENTICATION -> R.string.account_login
    state.conflicts.isNotEmpty() -> R.string.account_sync_conflicts
    !state.enabled -> R.string.account_sync_paused
    state.problem == SyncProblem.CONNECTION -> R.string.account_sync_waiting_network
    state.problem != null || state.pending > 0 || state.pendingMedia > 0 || state.waitingRows > 0 -> R.string.account_sync_waiting
    state.lastSuccessAt != null -> R.string.account_sync_done
    else -> R.string.account_sync_waiting
})

@Composable internal fun syncProblemText(problem: SyncProblem): String = stringResource(when (problem) {
    SyncProblem.SERVER_ADDRESS -> R.string.sync_error_endpoint
    SyncProblem.AUTHENTICATION -> R.string.sync_error_auth
    SyncProblem.INCOMPATIBLE -> R.string.sync_error_contract
    SyncProblem.STORAGE -> R.string.sync_error_storage
    SyncProblem.RESET -> R.string.sync_error_reset
    SyncProblem.ACCOUNT_REPLACEMENT -> R.string.sync_error_account
    SyncProblem.MEDIA -> R.string.sync_error_media
    SyncProblem.PENDING -> R.string.sync_error_pending
    SyncProblem.CONNECTION -> R.string.sync_error_connection
    SyncProblem.RATE_LIMIT -> R.string.account_error_rate
})

@Composable internal fun SyncDetailsScreen(state: SyncState, onBack: () -> Unit, onReset: () -> Unit) {
    var reset by rememberSaveable { mutableStateOf(false) }
    BackHandler { onBack() }
    RecoowPage(stringResource(R.string.settings_sync_details), navigationIcon = { SettingsBack(onBack) },
        contentSpacing = RecoowLayout.compactSectionGap) {
        if (state.running) item("progress") { SettingsProgress(stringResource(when (state.stage) {
            SyncStage.PREPARING -> R.string.sync_preparing; SyncStage.UPLOADING -> R.string.sync_uploading
            SyncStage.DOWNLOADING -> R.string.sync_downloading; SyncStage.APPLYING -> R.string.sync_applying
            else -> R.string.sync_working
        })) }
        item("state") { RecoowGroup {
            RecoowTextValueRow(stringResource(R.string.account_sync_status), syncStatusText(state))
            RecoowDivider()
            RecoowTextValueRow(stringResource(R.string.settings_last_sync), state.lastSuccessAt?.let {
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it))
            } ?: stringResource(R.string.settings_not_synced))
        } }
        item("pending") { SettingsSection(stringResource(R.string.sync_pending_title)) { RecoowGroup {
            RecoowMetricStrip(listOf(RecoowMetric(stringResource(R.string.settings_pending_records), state.pending.toString(), icon = DesignR.drawable.ic_tool_diary),
                RecoowMetric(stringResource(R.string.settings_pending_photos), state.pendingMedia.toString(), icon = DesignR.drawable.ic_photo)))
            if (state.waitingRows > 0) { RecoowDivider(); RecoowTextValueRow(stringResource(R.string.settings_waiting_relations), state.waitingRows.toString()) }
        } } }
        state.problem?.let { problem -> item("problem") { SettingsBanner(syncProblemText(problem), error = true) } }
        if (state.problem == SyncProblem.RESET) item("reset") { SettingsPrimaryButton(stringResource(R.string.sync_remerge), enabled = !state.running) { reset = true } }
        if (!state.enabled) item("hint") { SettingsFooter(stringResource(R.string.sync_disabled_hint)) }
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text(stringResource(R.string.sync_remerge)) },
        text = { Text(stringResource(R.string.sync_remerge_detail)) },
        confirmButton = { TextButton(onClick = { reset = false; onReset() }) { Text(stringResource(R.string.sync_remerge)) } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable internal fun SyncDevicesScreen(state: SyncState, onBack: () -> Unit, onRefresh: () -> Unit,
    onRevoke: (String) -> Unit, onPair: () -> Unit) {
    var revoke by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onRefresh() }
    BackHandler { onBack() }
    RecoowPage(stringResource(R.string.settings_devices), navigationIcon = { SettingsBack(onBack) },
        action = { IconButton(onClick = onRefresh, enabled = !state.running, modifier = Modifier.testTag("sync_devices_refresh")) {
            Icon(androidx.compose.ui.res.painterResource(DesignR.drawable.ic_setting_sync), stringResource(R.string.sync_devices))
        } }, contentSpacing = RecoowLayout.compactSectionGap) {
        item("devices") { SettingsSection(stringResource(R.string.sync_section_devices), state.devices.size.toString()) { RecoowGroup {
            if (state.devices.isEmpty()) RecoowEmptyState(stringResource(R.string.settings_no_devices), icon = DesignR.drawable.ic_setting_sync)
            state.devices.forEachIndexed { index, device ->
                val connection = state.connection
                val revocable = connection != null && if (connection.canPair) device.id != connection.serverDeviceId else device.id == connection.serverDeviceId
                val actions = if (revocable) listOf(RecoowAction(stringResource(R.string.sync_revoke), enabled = !state.running) { revoke = device.id }) else emptyList()
                RecoowRow(device.name, subtitle = listOfNotNull(stringResource(if (device.owner) R.string.sync_owner else R.string.sync_member),
                    if (device.id == connection?.serverDeviceId) stringResource(R.string.sync_this_device) else null, device.lastSeenAt.takeIf { it.isNotBlank() }).joinToString(" · "),
                    icon = { SettingsIcon(DesignR.drawable.ic_setting_sync) }, trailing = if (actions.isNotEmpty()) ({ RecoowOverflowMenu(actions) }) else null)
                if (index < state.devices.lastIndex) RecoowDivider(RecoowLayout.dividerInset)
            }
        } } }
        if (state.connection?.canPair == true) item("pair") { SettingsPrimaryButton(stringResource(R.string.sync_pair_device), enabled = !state.running,
            modifier = Modifier.testTag("sync_pair_device"), onClick = onPair) }
        state.problem?.let { item("problem") { SettingsFooter(syncProblemText(it), error = true) } }
    }
    revoke?.let { id -> AlertDialog(onDismissRequest = { revoke = null }, title = { Text(stringResource(R.string.sync_revoke)) },
        text = { Text(stringResource(R.string.sync_revoke_hint)) }, confirmButton = { TextButton(onClick = { revoke = null; onRevoke(id) }, enabled = !state.running) {
            Text(stringResource(R.string.sync_revoke), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { revoke = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable internal fun SyncConflictsScreen(state: SyncState, onBack: () -> Unit, onResolve: (RecordConflict, Boolean) -> Unit) {
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    BackHandler { onBack() }
    RecoowPage(stringResource(R.string.settings_conflicts), navigationIcon = { SettingsBack(onBack) }, contentSpacing = RecoowLayout.compactSectionGap) {
        item("hint") { SettingsFooter(stringResource(R.string.sync_conflict_merge_detail)) }
        if (state.conflicts.isEmpty()) item("empty") { RecoowGroup { RecoowEmptyState(stringResource(R.string.settings_no_conflicts), icon = DesignR.drawable.ic_check) } }
        state.conflicts.forEach { conflict -> item("${conflict.table}:${conflict.id}") { RecoowGroup {
            Column(Modifier.padding(RecoowLayout.rowHorizontal), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(conflict.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.sync_conflict_status, stringResource(if (conflict.localDeleted) R.string.sync_deleted else R.string.sync_retained),
                    stringResource(if (conflict.remoteDeleted) R.string.sync_deleted else R.string.sync_retained)), style = MaterialTheme.typography.bodyMedium)
                conflict.values.forEach { field ->
                    Text(if (chinese) field.chineseLabel else field.englishLabel, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.sync_local_value, field.local), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.sync_remote_value, field.remote), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    } }
                }
                if (conflict.values.isEmpty()) Text(stringResource(R.string.sync_conflict_metadata), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onResolve(conflict, true) }, enabled = !state.running, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.sync_keep_local)) }
                    TextButton(onClick = { onResolve(conflict, false) }, enabled = !state.running, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.sync_use_remote)) }
                }
            }
        } } }
    }
}

@Composable internal fun SyncCredentialDialogs(state: SyncState, onRecoverySaved: () -> Unit, onDismissPair: () -> Unit) {
    state.recoveryKey?.let { key -> AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.sync_recovery_key)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(stringResource(R.string.sync_recovery_detail)); SelectionContainer { Text(key) } } },
        confirmButton = { TextButton(onClick = onRecoverySaved) { Text(stringResource(R.string.sync_recovery_saved)) } }) }
    state.pairingCode?.let { code -> AlertDialog(onDismissRequest = onDismissPair, title = { Text(stringResource(R.string.sync_pairing_code)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(stringResource(R.string.sync_pair_detail)); SelectionContainer { Text(code, style = MaterialTheme.typography.bodyLarge) } } },
        confirmButton = { TextButton(onClick = onDismissPair) { Text(stringResource(R.string.sync_close)) } }) }
}
