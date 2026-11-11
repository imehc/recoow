package imehc.recoow.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as DesignR
import imehc.recoow.core.domain.sync.*

@Composable
fun AccountSecurityRoute(model: AccountSecurityViewModel, onBack: () -> Unit, onLogin: () -> Unit, onSync: () -> Unit,
    onDevices: () -> Unit = {}, onRevoke: (String) -> Unit = {}, onResolve: (RecordConflict, Boolean) -> Unit = { _, _ -> },
    onPair: () -> Unit = {}, onDismissPair: () -> Unit = {}, onRecoverySaved: () -> Unit = {}) {
    val account by model.state.collectAsStateWithLifecycle()
    val sync by model.syncState.collectAsStateWithLifecycle()
    AccountSecurityScreen(account, sync, onBack, onLogin, onSync, model::refresh, model::set, model::change,
        model::reset, model::synchronize, model::setSyncEnabled, model::disconnect, model::confirmReset,
        onDevices, onRevoke, onResolve, onPair, onDismissPair, onRecoverySaved)
}

private enum class PasswordMode { SET, CHANGE, RESET }
private enum class AccountPanel { DETAILS, DEVICES, CONFLICTS, SECURITY }

@Composable
fun AccountSecurityScreen(account: AccountSecurityState, sync: SyncState,
    onBack: () -> Unit, onLogin: () -> Unit, onSync: () -> Unit, onRefresh: () -> Unit,
    onSet: (String) -> Unit, onChange: (String, String) -> Unit, onReset: (String, String, String) -> Unit,
    onSynchronize: () -> Unit, onEnabled: (Boolean) -> Unit, onDisconnect: () -> Unit, onCloudReset: () -> Unit,
    onDevices: () -> Unit = {}, onRevoke: (String) -> Unit = {}, onResolve: (RecordConflict, Boolean) -> Unit = { _, _ -> },
    onPair: () -> Unit = {}, onDismissPair: () -> Unit = {}, onRecoverySaved: () -> Unit = {}) {
    var editing by rememberSaveable { mutableStateOf<PasswordMode?>(null) }
    var panel by rememberSaveable { mutableStateOf<AccountPanel?>(null) }
    var showingLogout by rememberSaveable { mutableStateOf(false) }
    var showingReset by rememberSaveable { mutableStateOf(false) }
    val connection = sync.connection
    SyncCredentialDialogs(sync, onRecoverySaved, onDismissPair)
    if (editing != null) {
        PasswordScreen(requireNotNull(editing), account, connection?.endpoint.orEmpty(), { editing = null }, onSet, onChange, onReset)
        return
    }
    val panelBack = { panel = null }
    when (panel) {
        AccountPanel.DETAILS -> { SyncDetailsScreen(sync, panelBack, onCloudReset); return }
        AccountPanel.DEVICES -> { SyncDevicesScreen(sync, panelBack, onDevices, onRevoke, onPair); return }
        AccountPanel.CONFLICTS -> { SyncConflictsScreen(sync, panelBack, onResolve); return }
        AccountPanel.SECURITY -> {
            BackHandler { panelBack() }
            RecoowPage(stringResource(R.string.account_password_security), navigationIcon = { AccountBackButton(panelBack) },
                contentSpacing = RecoowLayout.compactSectionGap) {
                item("password") { RecoowGroup {
                    if (account.username != null) RecoowRow(stringResource(R.string.account_change_password),
                        showChevron = true, onClick = { editing = PasswordMode.CHANGE }, modifier = Modifier.testTag("account_change_password"))
                    else if (connection?.canPair == true) RecoowRow(stringResource(R.string.account_set_password),
                        trailingText = stringResource(R.string.account_unset_short), showChevron = true,
                        onClick = { editing = PasswordMode.SET }, modifier = Modifier.testTag("account_set_password"))
                    else RecoowRow(stringResource(R.string.account_password), subtitle = stringResource(R.string.account_owner_set))
                    RecoowDivider()
                    RecoowRow(stringResource(R.string.account_reset_password), showChevron = true,
                        onClick = { editing = PasswordMode.RESET }, modifier = Modifier.testTag("account_reset_password"))
                } }
                item("hint") { SettingsFooter(stringResource(R.string.account_password_hint)) }
            }
            return
        }
        null -> Unit
    }
    RecoowPage(stringResource(R.string.account_security), navigationIcon = { AccountBackButton(onBack) },
        contentSpacing = RecoowLayout.compactSectionGap) {
        item("identity") { SettingsSection(stringResource(R.string.account_section)) { RecoowGroup {
            if (connection != null) {
                account.username?.let { username -> RecoowCopyableValueRow(stringResource(R.string.account_username), username,
                    stringResource(R.string.account_copy_username), stringResource(R.string.account_copied)) }
                    ?: RecoowTextValueRow(stringResource(R.string.account_username), stringResource(
                        if (account.loaded && account.supported) R.string.account_username_pending else R.string.account_loading))
            } else RecoowRow(stringResource(R.string.account_enable_cloud), showChevron = true, onClick = onLogin,
                icon = { SettingsIcon(R.drawable.ic_account_security) })
        } } }
        if (connection != null) {
            item("sync") { SettingsSection(stringResource(R.string.sync_section_sync)) { RecoowGroup {
                RecoowRow(stringResource(R.string.account_sync_status), subtitle = syncStatusText(sync), trailing = {
                    TextButton(onClick = onSynchronize, enabled = sync.enabled && !sync.running,
                        modifier = Modifier.testTag("sync_now")) { Text(stringResource(R.string.sync_now)) }
                })
                RecoowDivider()
                RecoowSwitchRow(stringResource(R.string.sync_enabled), sync.enabled,
                    { enabled -> if (enabled && sync.problem == SyncProblem.RESET) showingReset = true else onEnabled(enabled) },
                    enabled = !sync.running, modifier = Modifier.testTag("sync_enabled"))
                RecoowDivider()
                RecoowRow(stringResource(R.string.settings_sync_details), showChevron = true,
                    onClick = { panel = AccountPanel.DETAILS }, modifier = Modifier.testTag("sync_details"))
            } } }
            item("devices") { RecoowGroup {
                SettingsNavigationRow(DesignR.drawable.ic_setting_sync, stringResource(R.string.settings_devices), "account_devices",
                    value = sync.devices.size.toString(), onClick = { panel = AccountPanel.DEVICES })
                RecoowDivider(RecoowLayout.dividerInset)
                SettingsNavigationRow(DesignR.drawable.ic_clock, stringResource(R.string.settings_conflicts), "account_conflicts",
                    value = sync.conflicts.size.toString(), onClick = { panel = AccountPanel.CONFLICTS })
            } }
            if (sync.problem == SyncProblem.AUTHENTICATION) item("login") { RecoowGroup {
                RecoowRow(stringResource(R.string.account_login), showChevron = true, onClick = onLogin)
            } }
            if (account.loaded && account.supported && account.problem == null) item("security") { RecoowGroup {
                SettingsNavigationRow(R.drawable.ic_account_security, stringResource(R.string.account_password_security), "account_password_security",
                    onClick = { panel = AccountPanel.SECURITY })
            } }
            item("connection") { RecoowGroup {
                SettingsNavigationRow(DesignR.drawable.ic_setting_sync, stringResource(R.string.account_connection_settings), "account_connection",
                    subtitle = connection.endpoint, onClick = onSync)
            } }
            item("logout") { RecoowGroup { RecoowRow(stringResource(R.string.sync_disconnect), titleColor = MaterialTheme.colorScheme.error,
                onClick = if (!sync.running && !account.busy) ({ showingLogout = true }) else null, modifier = Modifier.testTag("account_logout")) } }
        } else item("connection") { SettingsSection(stringResource(R.string.account_connect_cloud)) { RecoowGroup {
            RecoowRow(stringResource(R.string.account_other_connection), showChevron = true, onClick = onSync)
            RecoowDivider()
            RecoowRow(stringResource(R.string.account_reset_password), showChevron = true, onClick = { editing = PasswordMode.RESET })
        } } }
        if ((connection != null && !account.loaded) || account.busy) item("loading") { SettingsProgress(stringResource(R.string.account_loading)) }
        if (connection != null && account.loaded && !account.supported && account.problem == null)
            item("unsupported") { SettingsBanner(stringResource(R.string.account_unsupported)) }
        account.result?.let { result -> item("result") { SettingsBanner(stringResource(when (result) {
            AccountResult.PASSWORD_SET -> R.string.account_password_set
            AccountResult.PASSWORD_CHANGED -> R.string.account_password_changed
            AccountResult.PASSWORD_RESET -> R.string.account_password_reset
        }) + (account.resetUsername?.let { "\n" + it } ?: "")) } }
        account.problem?.let { problem -> item("error") { SettingsBanner(accountProblemText(problem), error = true,
            actionLabel = stringResource(R.string.retry), onAction = onRefresh) } }
    }
    if (showingLogout) AlertDialog(onDismissRequest = { showingLogout = false },
        title = { Text(stringResource(R.string.sync_disconnect)) }, text = { Text(stringResource(R.string.sync_disconnect_detail)) },
        confirmButton = { TextButton(onClick = { showingLogout = false; onDisconnect() }, enabled = !sync.running && !account.busy) {
            Text(stringResource(R.string.sync_disconnect), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showingLogout = false }) { Text(stringResource(R.string.cancel)) } })
    if (showingReset) AlertDialog(onDismissRequest = { showingReset = false },
        title = { Text(stringResource(R.string.sync_remerge)) }, text = { Text(stringResource(R.string.sync_remerge_detail)) },
        confirmButton = { TextButton(onClick = { showingReset = false; onCloudReset() }, enabled = !sync.running) { Text(stringResource(R.string.sync_remerge)) } },
        dismissButton = { TextButton(onClick = { showingReset = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun PasswordScreen(mode: PasswordMode, account: AccountSecurityState, initialEndpoint: String,
    onBack: () -> Unit, onSet: (String) -> Unit, onChange: (String, String) -> Unit, onReset: (String, String, String) -> Unit) {
    // 密码与恢复密钥刻意不放入 rememberSaveable 和 ViewModel。
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
    }), navigationIcon = { AccountBackButton(onBack) }, contentSpacing = RecoowLayout.compactSectionGap) {
        item("fields") { RecordForm {
            if (mode == PasswordMode.RESET) {
                SettingsInput(stringResource(R.string.sync_endpoint), endpoint, { endpoint = it }, !account.busy,
                    keyboard = KeyboardType.Uri, error = if (attempted && endpoint.isBlank()) stringResource(R.string.sync_error_endpoint) else null)
                RecordFormDivider()
                SettingsInput(stringResource(R.string.sync_recovery_key), recovery, { recovery = it }, !account.busy, secret = true,
                    error = if (attempted && recovery.isBlank()) stringResource(R.string.account_enter_recovery) else null, tag = "account_recovery_key")
                RecordFormDivider()
            }
            if (mode == PasswordMode.CHANGE) {
                SettingsInput(stringResource(R.string.account_current_password), current, { current = it }, !account.busy, secret = true,
                    error = if (attempted && !AccountPasswordRules.valid(current)) stringResource(if (AccountPasswordRules.charactersValid(current))
                        R.string.account_password_length else R.string.account_password_characters) else null, tag = "account_current_password")
                RecordFormDivider()
            }
            SettingsInput(stringResource(R.string.account_new_password), next, { next = it }, !account.busy, secret = true,
                error = if (attempted && !AccountPasswordRules.charactersValid(next)) stringResource(R.string.account_password_characters)
                    else if (attempted && !AccountPasswordRules.valid(next)) stringResource(R.string.account_password_length) else null, tag = "account_new_password")
            RecordFormDivider()
            SettingsInput(stringResource(R.string.account_confirm_password), confirmation, { confirmation = it }, !account.busy, secret = true,
                error = if (attempted && next != confirmation) stringResource(R.string.account_password_mismatch) else null, tag = "account_confirm_password")
        } }
        item("hint") {
            SettingsFooter(stringResource(R.string.account_password_hint_short) + "\n" + stringResource(when (mode) {
                PasswordMode.SET -> R.string.account_password_hint
                PasswordMode.CHANGE -> R.string.account_change_hint
                PasswordMode.RESET -> R.string.account_reset_hint
            }))
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
