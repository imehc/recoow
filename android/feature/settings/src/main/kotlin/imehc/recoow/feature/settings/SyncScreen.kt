package imehc.recoow.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.RecoowDivider
import imehc.recoow.core.designsystem.RecoowGroup
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.RecoowNotice
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.designsystem.RecoowRow
import imehc.recoow.core.designsystem.RecoowSwitchRow
import imehc.recoow.core.designsystem.RecordChoice
import imehc.recoow.core.designsystem.R as DesignR
import imehc.recoow.core.domain.sync.*

@Composable
fun SyncScreen(state: SyncState, onBack: () -> Unit,
    onConnect: (String, ConnectionMethod, String, String) -> Unit, onSync: () -> Unit,
    onDisconnect: () -> Unit, onRecoverySaved: () -> Unit, onResolve: (RecordConflict, Boolean) -> Unit,
    onPairingCode: () -> Unit, onDismissPairingCode: () -> Unit, onEnabled: (Boolean) -> Unit = {},
    onDevices: () -> Unit = {}, onRevoke: (String) -> Unit = {},
    onLogin: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    initialMethod: ConnectionMethod = ConnectionMethod.PAIR, onReset: () -> Unit = {}) {
    var endpoint by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf(initialMethod) }
    var device by rememberSaveable { mutableStateOf("Android") }
    // Credentials must not enter savedInstanceState or screenshot-backed navigation state.
    var secret by remember { mutableStateOf("") }
    var disconnect by remember { mutableStateOf(false) }
    var revoke by rememberSaveable { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    var confirmingLogin by remember { mutableStateOf(false) }
    var confirmingReset by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var submittedLogin by remember { mutableStateOf(false) }
    var previousConnection by remember { mutableStateOf<SyncConnection?>(null) }
    LaunchedEffect(state.running, state.connection, state.problem) {
        if (submittedLogin && !state.running && (state.problem == null || state.problem == SyncProblem.RESET) && state.connection != null) {
            method = ConnectionMethod.PAIR
            secret = ""
            submittedLogin = false
        }
    }
    val chinese = LocalConfiguration.current.locales[0].language == "zh"

    Box(Modifier.fillMaxSize().imePadding()) {
    RecoowPage(stringResource(R.string.sync_title), navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.testTag("sync_back")) {
            Icon(painterResource(DesignR.drawable.ic_record_back), contentDescription = stringResource(R.string.sync_back))
        }
    }) {
        val connection = state.connection
        if (connection != null && method != ConnectionMethod.LOGIN) {
            item("account") {
                SettingsSection(stringResource(R.string.sync_section_server)) {
                    RecoowGroup {
                        RecoowRow(
                            connection.endpoint,
                            subtitle = stringResource(R.string.sync_account, connection.accountId),
                        )
                    }
                }
            }
            item("status") {
                SettingsSection(stringResource(R.string.sync_section_sync)) {
                    RecoowGroup {
                        RecoowSwitchRow(
                            title = stringResource(R.string.sync_enabled),
                            checked = state.enabled,
                            onChange = { enabled -> if (enabled && state.problem == SyncProblem.RESET) confirmingReset = true else onEnabled(enabled) },
                            enabled = !state.running,
                            modifier = Modifier.testTag("sync_enabled"),
                        )
                        RecoowDivider()
                        RecoowRow(
                            stringResource(R.string.sync_pending_title),
                            trailingText = stringResource(R.string.sync_pending_value, state.pending, state.pendingMedia),
                        )
                        state.lastSuccessAt?.let { at ->
                            RecoowDivider()
                            RecoowRow(stringResource(R.string.sync_last_success,
                                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))))
                        }
                    }
                }
            }
            item("sync") {
                SettingsPrimaryButton(
                    stringResource(R.string.sync_now),
                    enabled = !state.running && state.enabled,
                    modifier = Modifier.testTag("sync_now"),
                    onClick = onSync,
                )
            }
            state.problem?.let { problem ->
                item("problem") {
                    SettingsBanner(
                        stringResource(when (problem) {
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
                        }),
                        error = true,
                    )
                }
            }
            if (state.problem == SyncProblem.RESET) item("reset") { SettingsPrimaryButton(stringResource(R.string.sync_remerge), enabled = !state.running) { confirmingReset = true } }
            if (!state.enabled) item("disabled") { RecoowNotice(stringResource(R.string.sync_disabled_hint)) }
            if (state.conflicts.isNotEmpty()) {
                item("conflicts") {
                    SettingsSection(
                        stringResource(R.string.sync_section_conflicts),
                        trailing = state.conflicts.size.toString(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RecoowNotice(stringResource(R.string.sync_conflict))
                            state.conflicts.forEach { conflict ->
                                ConflictCard(conflict, chinese, enabled = !state.running, onResolve = onResolve)
                            }
                        }
                    }
                }
            }
            item("devices") {
                SettingsSection(
                    stringResource(R.string.sync_section_devices),
                    trailing = stringResource(R.string.sync_devices),
                    onTrailingClick = onDevices,
                ) {
                    RecoowGroup {
                        state.devices.forEachIndexed { index, device ->
                            val revocable = if (connection.canPair) device.id != connection.serverDeviceId
                            else device.id == connection.serverDeviceId
                            if (index > 0) RecoowDivider()
                            RecoowRow(
                                device.name,
                                subtitle = listOfNotNull(
                                    stringResource(if (device.owner) R.string.sync_owner else R.string.sync_member),
                                    if (device.id == connection.serverDeviceId) stringResource(R.string.sync_this_device) else null,
                                    device.lastSeenAt,
                                ).joinToString(" · "),
                                trailing = if (revocable) {
                                    {
                                        Text(
                                            stringResource(R.string.sync_revoke_short),
                                            modifier = Modifier
                                                .heightIn(min = 44.dp)
                                                .clickable(enabled = !state.running) { revoke = device.id }
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }
            item("actions") {
                RecoowGroup {
                    if (connection.canPair) {
                        RecoowRow(stringResource(R.string.sync_pair_device), onClick = onPairingCode)
                        RecoowDivider()
                    }
                    RecoowRow(
                        stringResource(R.string.sync_disconnect),
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { disconnect = true },
                    )
                }
            }
        } else {
            item("intro") { RecoowNotice(stringResource(R.string.sync_intro)) }
            if (method == ConnectionMethod.LOGIN) item("login_hint") { RecoowNotice(stringResource(R.string.account_login_merge_hint)) }
            item("server") {
                SettingsSection(stringResource(R.string.sync_section_server)) {
                    OutlinedTextField(
                        endpoint, { endpoint = it },
                        Modifier.fillMaxWidth().testTag("sync_endpoint"),
                        label = { Text(stringResource(R.string.sync_endpoint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = MaterialTheme.shapes.medium,
                        singleLine = true,
                        enabled = !state.running,
                    )
                }
            }
            item("method") {
                SettingsSection(stringResource(R.string.sync_section_sync)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecoowGroup {
                            RecordChoice(
                                label = stringResource(R.string.sync_method_label),
                                selected = method,
                                options = listOf(
                                    ConnectionMethod.CREATE to stringResource(R.string.sync_create),
                                    ConnectionMethod.PAIR to stringResource(R.string.sync_pair),
                                    ConnectionMethod.RECOVER to stringResource(R.string.sync_recover),
                                    ConnectionMethod.LOGIN to stringResource(R.string.account_login),
                                ),
                                onChange = { method = it; secret = "" },
                                enabled = !state.running,
                                bordered = false,
                            )
                        }
                        if (method == ConnectionMethod.LOGIN) OutlinedTextField(
                            username, { username = it }, Modifier.fillMaxWidth().testTag("account_login_username"),
                            label = { Text(stringResource(R.string.account_username)) },
                            shape = MaterialTheme.shapes.medium, singleLine = true, enabled = !state.running,
                        )
                        OutlinedTextField(
                            secret, { secret = it },
                            Modifier.fillMaxWidth().testTag("sync_secret"),
                            label = { Text(stringResource(when (method) {
                                ConnectionMethod.CREATE -> R.string.sync_bootstrap_token
                                ConnectionMethod.PAIR -> R.string.sync_pairing_code
                                ConnectionMethod.RECOVER -> R.string.sync_recovery_key
                                ConnectionMethod.LOGIN -> R.string.account_password
                            })) },
                            isError = attempted && method == ConnectionMethod.LOGIN && !AccountPasswordRules.valid(secret),
                            supportingText = if (attempted && method == ConnectionMethod.LOGIN && !AccountPasswordRules.valid(secret)) {
                                { Text(stringResource(if (AccountPasswordRules.charactersValid(secret)) R.string.account_password_length else R.string.account_password_characters)) }
                            } else if (method == ConnectionMethod.CREATE) {
                                { Text(stringResource(R.string.sync_bootstrap_detail)) }
                            } else null,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = MaterialTheme.shapes.medium,
                            singleLine = true,
                            enabled = !state.running,
                        )
                        if (method != ConnectionMethod.CREATE) OutlinedTextField(
                            device, { device = it },
                            Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.sync_device_name)) },
                            shape = MaterialTheme.shapes.medium,
                            singleLine = true,
                            enabled = !state.running,
                        )
                    }
                }
            }
            item("connect") {
                SettingsPrimaryButton(
                    stringResource(if (method == ConnectionMethod.LOGIN) R.string.account_login else R.string.sync_connect),
                    enabled = !state.running && endpoint.isNotBlank() && (method == ConnectionMethod.CREATE || secret.isNotBlank()) && (method != ConnectionMethod.LOGIN || username.isNotBlank()),
                    modifier = Modifier.testTag("sync_connect"),
                ) {
                    attempted = true
                    if (method != ConnectionMethod.LOGIN || AccountPasswordRules.valid(secret)) confirmingLogin = true
                }
            }
            state.problem?.let { problem ->
                item("problem") {
                    SettingsBanner(stringResource(when (problem) {
                        SyncProblem.SERVER_ADDRESS -> R.string.sync_error_endpoint
                        SyncProblem.AUTHENTICATION -> if (method == ConnectionMethod.LOGIN) R.string.account_error_auth else R.string.sync_error_auth
                        SyncProblem.INCOMPATIBLE -> R.string.sync_error_contract
                        SyncProblem.STORAGE -> R.string.sync_error_storage
                        SyncProblem.RESET -> R.string.sync_error_reset
                        SyncProblem.ACCOUNT_REPLACEMENT -> R.string.sync_error_account
                        SyncProblem.MEDIA -> R.string.sync_error_media
                        SyncProblem.PENDING -> R.string.sync_error_pending
                        SyncProblem.CONNECTION -> R.string.sync_error_connection
                        SyncProblem.RATE_LIMIT -> R.string.account_error_rate
                    }), error = true)
                }
            }
        }
        if (state.running) item("running") {
            SettingsProgress(stringResource(when (state.stage) {
                SyncStage.PREPARING -> R.string.sync_preparing
                SyncStage.UPLOADING -> R.string.sync_uploading
                SyncStage.DOWNLOADING -> R.string.sync_downloading
                SyncStage.APPLYING -> R.string.sync_applying
                SyncStage.IDLE -> R.string.sync_working
            }))
        }
    }

    }
    if (confirmingLogin) AlertDialog(onDismissRequest = { confirmingLogin = false },
        title = { Text(stringResource(R.string.account_login_confirm)) },
        text = { Text(stringResource(R.string.account_login_merge_hint)) },
        confirmButton = { TextButton(onClick = {
            confirmingLogin = false
            previousConnection = state.connection
            submittedLogin = true
            if (method == ConnectionMethod.LOGIN) onLogin(endpoint, username, secret, device)
            else { submittedLogin = false; onConnect(endpoint, method, secret, device); secret = "" }
        }) { Text(stringResource(R.string.account_login)) } },
        dismissButton = { TextButton(onClick = { confirmingLogin = false }) { Text(stringResource(R.string.cancel)) } })
    if (confirmingReset) AlertDialog(onDismissRequest = { confirmingReset = false },
        title = { Text(stringResource(R.string.sync_remerge)) }, text = { Text(stringResource(R.string.sync_remerge_detail)) },
        confirmButton = { TextButton(onClick = { confirmingReset = false; onReset() }) { Text(stringResource(R.string.sync_remerge)) } },
        dismissButton = { TextButton(onClick = { confirmingReset = false }) { Text(stringResource(R.string.cancel)) } })
    revoke?.let { id -> AlertDialog(onDismissRequest = { revoke = null }, title = { Text(stringResource(R.string.sync_revoke)) },
        text = { Text(stringResource(R.string.sync_revoke_hint)) }, confirmButton = { TextButton(onClick = { revoke = null; onRevoke(id) }) { Text(stringResource(R.string.sync_revoke)) } },
        dismissButton = { TextButton(onClick = { revoke = null }) { Text(stringResource(R.string.cancel)) } }) }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false },
        title = { Text(stringResource(R.string.sync_disconnect)) }, text = { Text(stringResource(R.string.sync_disconnect_detail)) },
        confirmButton = { TextButton(onClick = { disconnect = false; onDisconnect() }) { Text(stringResource(R.string.sync_disconnect)) } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text(stringResource(R.string.cancel)) } })
    state.recoveryKey?.let { key -> AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.sync_recovery_key)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.sync_recovery_detail)); SelectionContainer { Text(key) }
        } }, confirmButton = { TextButton(onClick = onRecoverySaved) { Text(stringResource(R.string.sync_recovery_saved)) } }) }
    state.pairingCode?.let { code -> AlertDialog(onDismissRequest = onDismissPairingCode, title = { Text(stringResource(R.string.sync_pairing_code)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.sync_pair_detail)); SelectionContainer { Text(code, style = MaterialTheme.typography.headlineMedium) }
        } }, confirmButton = { TextButton(onClick = onDismissPairingCode) { Text(stringResource(R.string.sync_close)) } }) }
}

/** 冲突：一条记录一组字段对照，保住「设备 / 服务端」两列的读法。 */
@Composable
private fun ConflictCard(conflict: RecordConflict, chinese: Boolean, enabled: Boolean, onResolve: (RecordConflict, Boolean) -> Unit) {
    RecoowGroup {
        Column(
            Modifier.padding(horizontal = RecoowLayout.rowHorizontal, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(conflict.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.sync_conflict_status,
                    stringResource(if (conflict.localDeleted) R.string.sync_deleted else R.string.sync_retained),
                    stringResource(if (conflict.remoteDeleted) R.string.sync_deleted else R.string.sync_retained)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            conflict.values.forEach { field ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (chinese) field.chineseLabel else field.englishLabel, style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Column {
                            Text(stringResource(R.string.sync_local_value, field.local), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.sync_remote_value, field.remote), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (conflict.values.isEmpty()) Text(stringResource(R.string.sync_conflict_metadata), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.sync_conflict_merge_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                listOf(R.string.sync_keep_local to true, R.string.sync_use_remote to false).forEach { (label, keepLocal) ->
                    Text(
                        stringResource(label),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(enabled = enabled) { onResolve(conflict, keepLocal) }
                            .padding(vertical = 10.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
