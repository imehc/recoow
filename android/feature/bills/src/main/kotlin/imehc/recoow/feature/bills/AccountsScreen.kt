package imehc.recoow.feature.bills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import imehc.recoow.core.domain.bills.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AccountsScreen(state: BillsState, viewModel: BillsViewModel, onBack: () -> Unit) {
    var deleting by remember { mutableStateOf<StoredValueAccount?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.accounts)) }, navigationIcon = { BackButton(onBack) }) },
        floatingActionButton = { if (!state.busy) ExtendedFloatingActionButton(onClick = { viewModel.editAccount() }) { Text(stringResource(R.string.add_account)) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.accounts.isEmpty()) item { Text(stringResource(R.string.empty_accounts)) }
            items(state.accounts, key = { it.id }) { account -> Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (account.kind == AccountKind.OTHER) R.string.other_account else labelId(account.kind.wire)))
                    Text(money(account.balance, locale), style = MaterialTheme.typography.titleLarge)
                    Text(pluralStringResource(R.plurals.account_references, account.references, account.references), style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton(onClick = { viewModel.editAccount(account) }, enabled = !state.busy) { Text(stringResource(R.string.edit)) }
                        TextButton(onClick = { deleting = account }, enabled = !state.busy && account.references == 0) { Text(stringResource(R.string.delete)) }
                    }
                }
            } }
        }
    }
    deleting?.let { account -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_account_question)) },
        confirmButton = { TextButton(onClick = { deleting = null; viewModel.deleteAccount(account) }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AccountEditor(draft: AccountDraft, busy: Boolean, viewModel: BillsViewModel) {
    val resources = LocalResources.current
    val defaultName = stringResource(if (draft.kind == AccountKind.OTHER) R.string.other_account else labelId(draft.kind.wire))
    Dialog(onDismissRequest = viewModel::cancelAccount, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(stringResource(if (draft.revision == null) R.string.add_account else R.string.edit_account)) }, navigationIcon = { BackButton(viewModel::cancelAccount) }, actions = {
                TextButton(enabled = !busy && (draft.balance.isBlank() || BillMoney.cents(draft.balance) != null), onClick = { viewModel.saveAccount(defaultName) }, modifier = Modifier.testTag("account_save")) { Text(stringResource(R.string.save)) }
            })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item { OutlinedTextField(draft.name, { viewModel.updateAccount(draft.copy(name = it)) }, label = { Text(stringResource(R.string.account_name)) }, placeholder = { Text(defaultName) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("account_name")) }
                item { ChoiceField(stringResource(R.string.account_kind), draft.kind, AccountKind.entries.map { it to resources.getString(if (it == AccountKind.OTHER) R.string.other_account else labelId(it.wire)) }, { viewModel.updateAccount(draft.copy(kind = it)) }, enabled = !busy) }
                item { OutlinedTextField(draft.balance, { viewModel.updateAccount(draft.copy(balance = it)) }, label = { Text(stringResource(R.string.balance)) }, singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("account_balance")) }
                item { OutlinedTextField(draft.note, { viewModel.updateAccount(draft.copy(note = it)) }, label = { Text(stringResource(R.string.note)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}
