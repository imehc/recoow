package imehc.recoow.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import imehc.recoow.core.designsystem.*
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
    // 凭据不会进入 savedInstanceState 或 ViewModel。
    var secret by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var submittedLogin by remember { mutableStateOf(false) }
    LaunchedEffect(state.running, state.connection, state.problem) {
        if (submittedLogin && !state.running && (state.problem == null || state.problem == SyncProblem.RESET) && state.connection != null) {
            method = ConnectionMethod.PAIR
            secret = ""
            submittedLogin = false
        }
    }
    val connected = state.connection != null && method != ConnectionMethod.LOGIN
    Box(Modifier.fillMaxSize().imePadding()) {
        RecoowPage(stringResource(if (connected) R.string.account_connection_settings else R.string.account_connect_cloud),
            navigationIcon = { SettingsBack(onBack, !state.running, "sync_back") }, contentSpacing = RecoowLayout.compactSectionGap) {
            if (connected) {
                val connection = requireNotNull(state.connection)
                item("connection") { SettingsSection(stringResource(R.string.sync_section_server)) { RecoowGroup {
                    RecoowTextValueRow(stringResource(R.string.settings_server_address), connection.endpoint)
                    RecoowDivider()
                    RecoowCopyableValueRow(stringResource(R.string.account_section), connection.accountId,
                        stringResource(R.string.settings_copy_account), stringResource(R.string.account_copied))
                    RecoowDivider()
                    RecoowTextValueRow(stringResource(R.string.settings_device_role),
                        stringResource(if (connection.canPair) R.string.sync_owner else R.string.sync_member))
                } } }
                item("hint") { SettingsFooter(stringResource(R.string.settings_connection_hint)) }
            } else {
                item("intro") { SettingsFooter(stringResource(R.string.sync_intro)) }
                item("form") { RecordForm {
                    SettingsInput(stringResource(R.string.settings_server_address), endpoint, { endpoint = it }, !state.running,
                        keyboard = KeyboardType.Uri, tag = "sync_endpoint")
                    RecordFormDivider()
                    RecordChoice(stringResource(R.string.sync_method_label), method,
                        listOf(ConnectionMethod.CREATE to stringResource(R.string.sync_create),
                            ConnectionMethod.PAIR to stringResource(R.string.sync_pair),
                            ConnectionMethod.RECOVER to stringResource(R.string.sync_recover),
                            ConnectionMethod.LOGIN to stringResource(R.string.account_login)),
                        { method = it; secret = ""; attempted = false }, enabled = !state.running, bordered = false)
                    RecordFormDivider()
                    if (method == ConnectionMethod.LOGIN) {
                        SettingsInput(stringResource(R.string.account_username), username, { username = it }, !state.running,
                            tag = "account_login_username")
                        RecordFormDivider()
                    }
                    SettingsInput(stringResource(when (method) {
                        ConnectionMethod.CREATE -> R.string.settings_registration_token
                        ConnectionMethod.PAIR -> R.string.sync_pairing_code
                        ConnectionMethod.RECOVER -> R.string.sync_recovery_key
                        ConnectionMethod.LOGIN -> R.string.account_password
                    }), secret, { secret = it }, !state.running, secret = true, tag = "sync_secret",
                        error = if (attempted && method == ConnectionMethod.LOGIN && !AccountPasswordRules.valid(secret))
                            stringResource(if (AccountPasswordRules.charactersValid(secret)) R.string.account_password_length else R.string.account_password_characters) else null)
                    if (method != ConnectionMethod.CREATE) {
                        RecordFormDivider()
                        SettingsInput(stringResource(R.string.sync_device_name), device, { device = it }, !state.running, tag = "sync_device_name")
                    }
                } }
                if (method == ConnectionMethod.CREATE) item("token_hint") { SettingsFooter(stringResource(R.string.sync_bootstrap_detail)) }
                if (method == ConnectionMethod.LOGIN) item("password_hint") { SettingsFooter(stringResource(R.string.account_password_hint_short)) }
                item("merge_hint") { SettingsFooter(stringResource(R.string.account_login_merge_hint)) }
                item("connect") { SettingsPrimaryButton(stringResource(if (method == ConnectionMethod.LOGIN) R.string.account_login else R.string.sync_connect),
                    enabled = !state.running && endpoint.isNotBlank() && (method == ConnectionMethod.CREATE || secret.isNotBlank()) &&
                        (method != ConnectionMethod.LOGIN || username.isNotBlank()), modifier = Modifier.testTag("sync_connect")) {
                    attempted = true
                    if (method != ConnectionMethod.LOGIN || AccountPasswordRules.valid(secret)) confirming = true
                } }
                state.problem?.let { problem -> item("problem") { SettingsBanner(
                    if (problem == SyncProblem.AUTHENTICATION && method == ConnectionMethod.LOGIN) stringResource(R.string.account_error_auth)
                    else syncProblemText(problem), error = true) } }
            }
            if (state.running) item("running") { SettingsProgress(stringResource(when (state.stage) {
                SyncStage.PREPARING -> R.string.sync_preparing; SyncStage.UPLOADING -> R.string.sync_uploading
                SyncStage.DOWNLOADING -> R.string.sync_downloading; SyncStage.APPLYING -> R.string.sync_applying
                else -> R.string.sync_working
            })) }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(stringResource(if (method == ConnectionMethod.LOGIN) R.string.account_login_confirm else R.string.settings_connect_confirm)) },
        text = { Text(stringResource(R.string.account_login_merge_hint)) },
        confirmButton = { TextButton(onClick = {
            confirming = false
            if (method == ConnectionMethod.LOGIN) { submittedLogin = true; onLogin(endpoint, username, secret, device) }
            else { onConnect(endpoint, method, secret, device); secret = "" }
        }, enabled = !state.running) { Text(stringResource(if (method == ConnectionMethod.LOGIN) R.string.account_login else R.string.sync_connect)) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } })
    SyncCredentialDialogs(state, onRecoverySaved, onDismissPairingCode)
}
