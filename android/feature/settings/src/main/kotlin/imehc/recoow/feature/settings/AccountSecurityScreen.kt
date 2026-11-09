package imehc.recoow.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as DesignR
import imehc.recoow.core.domain.sync.*

@Composable
fun AccountSecurityRoute(model: AccountSecurityViewModel, onBack: () -> Unit, onLogin: () -> Unit, onSync: () -> Unit) {
    val account by model.state.collectAsStateWithLifecycle()
    val sync by model.syncState.collectAsStateWithLifecycle()
    AccountSecurityScreen(account, sync, onBack, onLogin, onSync, model::refresh, model::set, model::change, model::reset, model::synchronize, model::setSyncEnabled, model::disconnect, model::confirmReset)
}

private enum class PasswordMode { SET, CHANGE, RESET }

@Composable
fun AccountSecurityScreen(account: AccountSecurityState, sync: SyncState,
    onBack: () -> Unit, onLogin: () -> Unit, onSync: () -> Unit, onRefresh: () -> Unit,
    onSet: (String) -> Unit, onChange: (String, String) -> Unit, onReset: (String, String, String) -> Unit, onSynchronize: () -> Unit, onEnabled: (Boolean) -> Unit, onDisconnect: () -> Unit, onCloudReset: () -> Unit) {
    var editing by remember { mutableStateOf<PasswordMode?>(null) }
    var showingLogout by remember { mutableStateOf(false) }
    var showingReset by remember { mutableStateOf(false) }
    val connection = sync.connection
    if (editing != null) {
        PasswordScreen(requireNotNull(editing), account, connection?.endpoint.orEmpty(), { editing = null }, onSet, onChange, onReset)
        return
    }
    RecoowPage(stringResource(R.string.account_security), navigationIcon = { AccountBackButton(onBack) }) {
        item("identity") {
            SettingsSection(stringResource(R.string.account_section)) {
                RecoowGroup {
                    if (connection != null) {
                        account.username?.let { username ->
                            RecoowCopyableValueRow(stringResource(R.string.account_username), username,
                                stringResource(R.string.account_copy_username), stringResource(R.string.account_copied))
                        } ?: RecoowRow(stringResource(R.string.account_username),
                            trailingText = stringResource(if (account.loaded && account.supported) R.string.account_username_pending else R.string.account_loading))
                        RecoowDivider()
                        RecoowRow(stringResource(R.string.sync_section_server), subtitle = connection.endpoint)
                    } else {
                        RecoowRow(stringResource(R.string.account_enable_cloud), showChevron = true, onClick = onLogin)
                    }
                }
            }
        }
        if (connection != null) item("sync") {
            SettingsSection(stringResource(R.string.account_sync_devices)) {
                RecoowGroup {
                    RecoowRow(stringResource(R.string.account_sync_status), trailingText = stringResource(when {
                        sync.running -> R.string.account_sync_running
                        sync.conflicts.isNotEmpty() -> R.string.account_sync_conflicts
                        !sync.enabled -> R.string.account_sync_paused
                        sync.problem == SyncProblem.CONNECTION -> R.string.account_sync_waiting_network
                        sync.problem != null || sync.pending > 0 || sync.pendingMedia > 0 || sync.waitingRows > 0 -> R.string.account_sync_waiting
                        sync.lastSuccessAt != null -> R.string.account_sync_done
                        else -> R.string.account_sync_waiting
                    }))
                    RecoowDivider()
                    RecoowSwitchRow(stringResource(R.string.sync_enabled), sync.enabled,
                        { enabled -> if (enabled && sync.problem == SyncProblem.RESET) showingReset = true else onEnabled(enabled) })
                    RecoowDivider()
                    RecoowRow(stringResource(R.string.sync_now), onClick = onSynchronize.takeIf { sync.enabled && !sync.running }, titleColor = if (sync.enabled && !sync.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    RecoowDivider()
                    RecoowRow(stringResource(R.string.account_connection_settings), showChevron = true, onClick = onSync)
                    if (sync.problem == SyncProblem.AUTHENTICATION) {
                        RecoowDivider()
                        RecoowRow(stringResource(R.string.account_login), showChevron = true, onClick = onLogin)
                    }
                }
            }
        }
        if (connection != null) item("logout") {
            RecoowGroup { RecoowRow(stringResource(R.string.sync_disconnect), titleColor = MaterialTheme.colorScheme.error, onClick = { showingLogout = true }) }
        }
        if (connection != null && !account.loaded || account.busy) item("loading") { SettingsProgress(stringResource(R.string.account_loading)) }
        if (connection != null && account.loaded && account.supported && account.problem == null) {
            item("password") {
                SettingsSection(stringResource(R.string.account_password_security)) {
                    RecoowGroup {
                        if (account.username != null) {
                            RecoowRow(stringResource(R.string.account_change_password), showChevron = true, onClick = { editing = PasswordMode.CHANGE })
                        } else if (connection.canPair) {
                            RecoowRow(stringResource(R.string.account_set_password), trailingText = stringResource(R.string.account_unset_short),
                                showChevron = true, onClick = { editing = PasswordMode.SET })
                        } else RecoowRow(stringResource(R.string.account_password), subtitle = stringResource(R.string.account_owner_set))
                        RecoowDivider()
                        RecoowRow(stringResource(R.string.account_reset_password), showChevron = true, onClick = { editing = PasswordMode.RESET })
                    }
                }
            }
        }
        if (connection == null) item("connection") {
            SettingsSection(stringResource(R.string.account_connect_cloud)) {
                RecoowGroup {
                    RecoowRow(stringResource(R.string.account_other_connection), showChevron = true, onClick = onSync)
                    RecoowDivider()
                    RecoowRow(stringResource(R.string.account_reset_password), showChevron = true, onClick = { editing = PasswordMode.RESET })
                }
            }
        }
        if (connection != null && account.loaded && !account.supported && account.problem == null) {
            item("unsupported") { SettingsBanner(stringResource(R.string.account_unsupported)) }
        }
        account.result?.let { result -> item("result") {
            SettingsBanner(stringResource(when (result) {
                AccountResult.PASSWORD_SET -> R.string.account_password_set
                AccountResult.PASSWORD_CHANGED -> R.string.account_password_changed
                AccountResult.PASSWORD_RESET -> R.string.account_password_reset
            }) + (account.resetUsername?.let { "\n" + it } ?: ""))
        } }
        account.problem?.let { problem -> item("error") {
            SettingsBanner(accountProblemText(problem), error = true, actionLabel = stringResource(R.string.retry), onAction = onRefresh)
        } }
    }
    if (showingLogout) AlertDialog(onDismissRequest = { showingLogout = false },
        title = { Text(stringResource(R.string.sync_disconnect)) }, text = { Text(stringResource(R.string.sync_disconnect_detail)) },
        confirmButton = { TextButton(onClick = { showingLogout = false; onDisconnect() }) { Text(stringResource(R.string.sync_disconnect)) } },
        dismissButton = { TextButton(onClick = { showingLogout = false }) { Text(stringResource(R.string.cancel)) } })
    if (showingReset) AlertDialog(onDismissRequest = { showingReset = false },
        title = { Text(stringResource(R.string.sync_remerge)) }, text = { Text(stringResource(R.string.sync_remerge_detail)) },
        confirmButton = { TextButton(onClick = { showingReset = false; onCloudReset() }) { Text(stringResource(R.string.sync_remerge)) } },
        dismissButton = { TextButton(onClick = { showingReset = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PasswordScreen(mode: PasswordMode, account: AccountSecurityState, initialEndpoint: String,
    onBack: () -> Unit, onSet: (String) -> Unit, onChange: (String, String) -> Unit, onReset: (String, String, String) -> Unit) {
    // Passwords and recovery keys are intentionally absent from rememberSaveable and the ViewModel.
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(initialEndpoint) }
    var attempted by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    LaunchedEffect(account.busy, account.result) {
        if (submitted && !account.busy && account.result != null) onBack()
    }
    Box(Modifier.fillMaxSize().imePadding()) {
    RecoowPage(stringResource(when (mode) {
        PasswordMode.SET -> R.string.account_set_password
        PasswordMode.CHANGE -> R.string.account_change_password
        PasswordMode.RESET -> R.string.account_reset_password
    }), navigationIcon = { AccountBackButton(onBack) }) {
        if (mode == PasswordMode.RESET) {
            item("endpoint") { RecordField(stringResource(R.string.sync_endpoint), endpoint, { endpoint = it }, !account.busy,
                error = if (attempted && endpoint.isBlank()) stringResource(R.string.sync_error_endpoint) else null) }
            item("recovery") { AccountSecretField(stringResource(R.string.sync_recovery_key), recovery, { recovery = it }, !account.busy,
                if (attempted && recovery.isBlank()) stringResource(R.string.account_enter_recovery) else null, "account_recovery_key") }
        }
        if (mode == PasswordMode.CHANGE) item("current") {
            AccountSecretField(stringResource(R.string.account_current_password), current, { current = it }, !account.busy,
                if (attempted && !AccountPasswordRules.valid(current)) stringResource(if (AccountPasswordRules.charactersValid(current)) R.string.account_password_length else R.string.account_password_characters) else null, "account_current_password")
        }
        item("next") { AccountSecretField(stringResource(R.string.account_new_password), next, { next = it }, !account.busy,
            if (attempted && !AccountPasswordRules.charactersValid(next)) stringResource(R.string.account_password_characters)
            else if (attempted && !AccountPasswordRules.valid(next)) stringResource(R.string.account_password_length) else null, "account_new_password", hint = stringResource(R.string.account_password_hint_short)) }
        item("confirmation") { AccountSecretField(stringResource(R.string.account_confirm_password), confirmation, { confirmation = it }, !account.busy,
            if (attempted && next != confirmation) stringResource(R.string.account_password_mismatch) else null, "account_confirm_password") }
        item("purpose") {
            Text(stringResource(when (mode) {
                PasswordMode.SET -> R.string.account_password_hint
                PasswordMode.CHANGE -> R.string.account_change_hint
                PasswordMode.RESET -> R.string.account_reset_hint
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = RecoowLayout.rowHorizontal))
        }
        item("save") {
            SettingsPrimaryButton(stringResource(R.string.account_save), enabled = !account.busy, modifier = Modifier.testTag("account_password_save")) {
                attempted = true
                if (AccountPasswordRules.valid(next) && next == confirmation && (mode != PasswordMode.CHANGE || AccountPasswordRules.valid(current)) &&
                    (mode != PasswordMode.RESET || recovery.isNotBlank() && endpoint.isNotBlank())) {
                    submitted = true
                    when (mode) {
                        PasswordMode.SET -> onSet(next)
                        PasswordMode.CHANGE -> onChange(current, next)
                        PasswordMode.RESET -> onReset(endpoint, recovery, next)
                    }
                }
            }
        }
        if (submitted) account.problem?.let { problem -> item("error") { SettingsBanner(accountProblemText(problem), error = true) } }
        if (account.busy) item("busy") { SettingsProgress(stringResource(R.string.account_saving)) }
    }
    }
}

@Composable
private fun AccountSecretField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, error: String?, tag: String, hint: String? = null) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag(tag), label = { Text(label) },
        enabled = enabled, isError = error != null, supportingText = (error ?: hint)?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = MaterialTheme.shapes.medium, singleLine = true)
}

@Composable
private fun AccountBackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(painterResource(DesignR.drawable.ic_record_back), contentDescription = stringResource(R.string.sync_back)) }
}

@Composable
private fun accountProblemText(problem: AccountProblem): String = stringResource(when (problem) {
    AccountProblem.CONNECTION -> R.string.account_error_connection
    AccountProblem.AUTHENTICATION -> R.string.account_error_auth
    AccountProblem.PERMISSION -> R.string.account_error_permission
    AccountProblem.UNSUPPORTED -> R.string.account_unsupported
    AccountProblem.CONFLICT -> R.string.account_error_conflict
    AccountProblem.RATE_LIMIT -> R.string.account_error_rate
    AccountProblem.PASSWORD -> R.string.account_password_length
    AccountProblem.STORAGE -> R.string.sync_error_storage
})
