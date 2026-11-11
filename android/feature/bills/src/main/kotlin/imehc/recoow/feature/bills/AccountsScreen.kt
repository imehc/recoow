package imehc.recoow.feature.bills

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import imehc.recoow.core.designsystem.RecoowCompactTopBar
import imehc.recoow.core.designsystem.RecordChoice
import imehc.recoow.core.designsystem.RecordForm
import imehc.recoow.core.designsystem.RecordFormDivider
import imehc.recoow.core.designsystem.RecordFormText
import imehc.recoow.core.designsystem.RecoowFloatingAddAction
import imehc.recoow.core.designsystem.RecoowDivider
import imehc.recoow.core.designsystem.RecoowEmptyState
import imehc.recoow.core.designsystem.RecoowGroup
import imehc.recoow.core.designsystem.RecoowLayout
import imehc.recoow.core.designsystem.RecoowRow
import imehc.recoow.core.domain.bills.*

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun AccountsScreen(state: BillsState, viewModel: BillsViewModel, onBack: () -> Unit) {
    var deleting by remember { mutableStateOf<StoredValueAccount?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RecoowCompactTopBar(stringResource(R.string.accounts), navigationIcon = { BackButton(onBack) }) },
        floatingActionButton = { if (!state.busy) RecoowFloatingAddAction(stringResource(R.string.add_account), onClick = { viewModel.editAccount() }, tag = "account_add") },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.sectionGap),
        ) {
            if (state.busy) item(key = "busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.accounts.isEmpty()) item(key = "empty") {
                RecoowGroup { RecoowEmptyState(stringResource(R.string.empty_accounts)) }
            }
            items(state.accounts, key = { it.id }) { account ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    RecoowGroup {
                        RecoowRow(
                            title = account.name,
                            subtitle = stringResource(if (account.kind == AccountKind.OTHER) R.string.other_account else labelId(account.kind.wire)),
                            trailingText = money(account.balance, locale),
                            modifier = Modifier.combinedClickable(
                                enabled = !state.busy,
                                onClick = { viewModel.editAccount(account) },
                                onLongClick = { menu = true },
                            ),
                        )
                    }
                    DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.medium,
                        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.edit)) },
                            onClick = { menu = false; viewModel.editAccount(account) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) },
                            enabled = !state.busy && account.references == 0,
                            onClick = { menu = false; deleting = account })
                    }
                }
            }
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
    var attempted by rememberSaveable { mutableStateOf(false) }
    val balanceOk = draft.balance.isBlank() || BillMoney.cents(draft.balance) != null
    val balanceError = if (attempted && !balanceOk) stringResource(R.string.amount_invalid) else null
    Dialog(onDismissRequest = viewModel::cancelAccount, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(stringResource(if (draft.revision == null) R.string.add_account else R.string.edit_account)) }, navigationIcon = { BackButton(viewModel::cancelAccount) }, actions = {
                TextButton(enabled = !busy, onClick = { if (balanceOk) viewModel.saveAccount(defaultName) else attempted = true }, modifier = Modifier.testTag("account_save")) { Text(stringResource(R.string.save)) }
            })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    RecordForm {
                        RecordFormText(stringResource(R.string.account_name), draft.name, { viewModel.updateAccount(draft.copy(name = it)) },
                            enabled = !busy, placeholder = defaultName, tag = "account_name")
                        RecordFormDivider()
                        RecordChoice(stringResource(R.string.account_kind), draft.kind,
                            AccountKind.entries.map { it to resources.getString(if (it == AccountKind.OTHER) R.string.other_account else labelId(it.wire)) },
                            { viewModel.updateAccount(draft.copy(kind = it)) }, enabled = !busy, bordered = false)
                        RecordFormDivider()
                        RecordFormText(stringResource(R.string.balance), draft.balance, { viewModel.updateAccount(draft.copy(balance = it)) },
                            enabled = !busy, keyboard = KeyboardType.Decimal, error = balanceError, tag = "account_balance")
                        RecordFormDivider()
                        RecordFormText(stringResource(R.string.note), draft.note, { viewModel.updateAccount(draft.copy(note = it)) }, enabled = !busy, multiline = true)
                    }
                }
            }
        }
    }
}
