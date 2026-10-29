package imehc.recoow.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.domain.sync.*

@Composable
fun SyncScreen(state: SyncState, onBack: () -> Unit,
    onConnect: (String, ConnectionMethod, String, String, Boolean) -> Unit, onSync: () -> Unit,
    onDisconnect: () -> Unit, onRecoverySaved: () -> Unit, onResolve: (RecordConflict, Boolean) -> Unit,
    onPairingCode: () -> Unit, onDismissPairingCode: () -> Unit) {
    var endpoint by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf(ConnectionMethod.PAIR) }
    var device by rememberSaveable { mutableStateOf("Android") }
    // Credentials must not enter savedInstanceState or screenshot-backed navigation state.
    var secret by remember { mutableStateOf("") }
    var disconnect by remember { mutableStateOf(false) }
    var replace by remember { mutableStateOf(false) }
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    RecoowPage(stringResource(R.string.sync_title), navigationIcon = {
        IconButton(onClick = onBack, modifier = Modifier.testTag("sync_back")) {
            Icon(painterResource(R.drawable.ic_sync_back), contentDescription = stringResource(R.string.sync_back))
        }
    }) {
        val connection = state.connection
        if (connection != null) {
            item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(connection.endpoint, style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(stringResource(R.string.sync_account, connection.accountId), style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.sync_counts, state.pending, state.conflicts.size, state.pendingMedia))
                Button(onClick = onSync, enabled = !state.running, modifier = Modifier.testTag("sync_now")) { Text(stringResource(R.string.sync_now)) }
                if (connection.canPair) TextButton(onClick = onPairingCode, enabled = !state.running) { Text(stringResource(R.string.sync_pair_device)) }
                TextButton(onClick = { disconnect = true }, enabled = !state.running) { Text(stringResource(R.string.sync_disconnect)) }
            } } }
        } else {
            item { Text(stringResource(R.string.sync_intro), style = MaterialTheme.typography.bodyLarge) }
            item { OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth().testTag("sync_endpoint"),
                label = { Text(stringResource(R.string.sync_endpoint)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, enabled = !state.running) }
            item { Column(Modifier.selectableGroup()) { ConnectionMethod.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().selectable(method == option, enabled = !state.running, role = Role.RadioButton,
                    onClick = { method = option; secret = "" }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(method == option, onClick = null)
                    Text(stringResource(when (option) { ConnectionMethod.CREATE -> R.string.sync_create; ConnectionMethod.PAIR -> R.string.sync_pair; ConnectionMethod.RECOVER -> R.string.sync_recover }), Modifier.padding(start = 12.dp))
                }
            } } }
            item { OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth().testTag("sync_secret"),
                label = { Text(stringResource(when (method) { ConnectionMethod.CREATE -> R.string.sync_bootstrap_token; ConnectionMethod.PAIR -> R.string.sync_pairing_code; ConnectionMethod.RECOVER -> R.string.sync_recovery_key })) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !state.running) }
            if (method != ConnectionMethod.CREATE) item { OutlinedTextField(device, { device = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.sync_device_name)) }, singleLine = true, enabled = !state.running) }
            item { Button(onClick = { onConnect(endpoint, method, secret, device, false); secret = "" },
                enabled = !state.running && endpoint.isNotBlank() && (method == ConnectionMethod.CREATE || secret.isNotBlank()), modifier = Modifier.testTag("sync_connect")) {
                Text(stringResource(R.string.sync_connect))
            } }
        }
        if (state.running) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.problem?.let { problem -> item {
            Text(stringResource(when (problem) {
                SyncProblem.AUTHENTICATION -> R.string.sync_error_auth
                SyncProblem.INCOMPATIBLE -> R.string.sync_error_contract
                SyncProblem.STORAGE -> R.string.sync_error_storage
                SyncProblem.RESET -> R.string.sync_error_reset
                SyncProblem.ACCOUNT_REPLACEMENT -> R.string.sync_error_account
                SyncProblem.MEDIA -> R.string.sync_error_media
                SyncProblem.PENDING -> R.string.sync_error_pending
                SyncProblem.CONNECTION -> R.string.sync_error_connection
            }), color = MaterialTheme.colorScheme.error)
            if (problem == SyncProblem.ACCOUNT_REPLACEMENT) TextButton(onClick = { replace = true }) { Text(stringResource(R.string.sync_replace)) }
        } }
        state.conflicts.forEach { conflict -> item(key = "${conflict.table}/${conflict.id}") {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sync_conflict), style = MaterialTheme.typography.titleMedium)
                Text(conflict.id, style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.sync_conflict_status,
                    stringResource(if (conflict.localDeleted) R.string.sync_deleted else R.string.sync_retained),
                    stringResource(if (conflict.remoteDeleted) R.string.sync_deleted else R.string.sync_retained)))
                conflict.values.forEach { field ->
                    Text(if (chinese) field.chineseLabel else field.englishLabel, style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Column {
                        Text(stringResource(R.string.sync_local_value, field.local), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.sync_remote_value, field.remote), style = MaterialTheme.typography.bodyMedium)
                    } }
                }
                if (conflict.values.isEmpty()) Text(stringResource(R.string.sync_conflict_metadata))
                TextButton(onClick = { onResolve(conflict, true) }, enabled = !state.running) { Text(stringResource(R.string.sync_keep_local)) }
                TextButton(onClick = { onResolve(conflict, false) }, enabled = !state.running) { Text(stringResource(R.string.sync_use_remote)) }
            } }
        } }
    }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false },
        title = { Text(stringResource(R.string.sync_disconnect)) }, text = { Text(stringResource(R.string.sync_disconnect_detail)) },
        confirmButton = { TextButton(onClick = { disconnect = false; onDisconnect() }) { Text(stringResource(R.string.sync_disconnect)) } },
        dismissButton = { TextButton(onClick = { disconnect = false }) { Text(stringResource(R.string.cancel)) } })
    if (replace) AlertDialog(onDismissRequest = { replace = false }, title = { Text(stringResource(R.string.sync_replace)) },
        text = { Text(stringResource(R.string.sync_replace_detail)) },
        confirmButton = { TextButton(onClick = { replace = false; onConnect(endpoint, method, secret, device, true) }) { Text(stringResource(R.string.sync_replace)) } },
        dismissButton = { TextButton(onClick = { replace = false }) { Text(stringResource(R.string.cancel)) } })
    state.recoveryKey?.let { key -> AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.sync_recovery_key)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.sync_recovery_detail)); SelectionContainer { Text(key) }
        } }, confirmButton = { TextButton(onClick = onRecoverySaved) { Text(stringResource(R.string.sync_recovery_saved)) } }) }
    state.pairingCode?.let { code -> AlertDialog(onDismissRequest = onDismissPairingCode, title = { Text(stringResource(R.string.sync_pairing_code)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.sync_pair_detail)); SelectionContainer { Text(code, style = MaterialTheme.typography.headlineMedium) }
        } }, confirmButton = { TextButton(onClick = onDismissPairingCode) { Text(stringResource(R.string.sync_close)) } }) }
}
