package imehc.recoow.feature.bills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.media.PhotoInput
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BillEditor(draft: BillDraft, state: BillsState, viewModel: BillsViewModel, onBack: () -> Unit) {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val enabled = !state.busy
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(if (draft.revision == null) R.string.add_bill else R.string.edit_bill)) },
            navigationIcon = { BackButton(onBack) }, actions = {
                TextButton(enabled = enabled && draft.valid, onClick = viewModel::save, modifier = Modifier.testTag("bill_save")) { Text(stringResource(R.string.save)) }
            })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("bill_form"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { OutlinedTextField(draft.title, { viewModel.update(draft.copy(title = it)) }, label = { Text(stringResource(R.string.title)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("bill_title")) }
            item { ChoiceField(stringResource(R.string.bills), draft.type, BillType.entries.map { it to resources.getString(labelId(it.wire)) }, { viewModel.update(draft.withType(it)) }, modifier = Modifier.testTag("bill_type"), enabled = enabled) }
            if (draft.type == BillType.EXPENSE) item { AmountField(draft, AmountField.ORIGINAL, R.string.original, enabled, viewModel) }
            item { AmountField(draft, AmountField.FINAL, when (draft.type) { BillType.EXPENSE -> R.string.final_amount; BillType.INCOME -> R.string.amount; else -> R.string.deduction }, enabled, viewModel) }
            if (draft.type == BillType.EXPENSE) {
                item { AmountField(draft, AmountField.DISCOUNT, R.string.discount, enabled, viewModel) }
                item { AmountField(draft, AmountField.RATE, R.string.rate, enabled, viewModel) }
            }
            if (draft.amounts.values(draft.type) == null && (draft.amounts.original.isNotBlank() || draft.amounts.final.isNotBlank())) item {
                Text(stringResource(R.string.amount_invalid), color = MaterialTheme.colorScheme.error)
            }
            item { ChoiceField(stringResource(R.string.category), draft.category,
                (if (draft.type == BillType.INCOME) incomeCategories else expenseCategories).map {
                    it to resources.getString(if (draft.type == BillType.INCOME && it == "other") R.string.other_income else labelId(it))
                }, { viewModel.update(draft.copy(category = it)) }, modifier = Modifier.testTag("bill_category"), enabled = enabled) }
            if (draft.type != BillType.STORED_VALUE_USE) item { ChoiceField(stringResource(if (draft.type == BillType.INCOME) R.string.channel else R.string.payment), draft.payment,
                PaymentMethod.entries.map { it to resources.getString(labelId(it.wire)) }, { viewModel.update(draft.copy(payment = it)) }, enabled = enabled) }
            item { DateTimeField(stringResource(R.string.date), draft.occurredAt, { viewModel.update(draft.copy(occurredAt = it)) }, enabled) }
            if (draft.isGroupBuy) item { DateTimeField(stringResource(R.string.valid_until), draft.validUntil, { viewModel.update(draft.copy(validUntil = it)) }, enabled) }
            if (draft.type != BillType.INCOME) item { Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (draft.type == BillType.EXPENSE) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.recharge), Modifier.weight(1f))
                        Switch(draft.recharge, { viewModel.update(draft.copy(recharge = it)) }, enabled = enabled, modifier = Modifier.testTag("bill_recharge"))
                    }
                    if (draft.requiresAccount) {
                        ChoiceField(stringResource(R.string.select_account), draft.accountId, state.accounts.map { it.id as String? to it.name },
                            { viewModel.update(draft.copy(accountId = it)) }, enabled = enabled)
                        state.accounts.firstOrNull { it.id == draft.accountId }?.let { account ->
                            Text("${stringResource(R.string.balance)}: ${money(account.balance, locale)}")
                        }
                        TextButton(onClick = { viewModel.editAccount() }, enabled = enabled, modifier = Modifier.testTag("bill_add_account")) { Text(stringResource(R.string.add_account)) }
                        Text(stringResource(R.string.recharge_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
            if (draft.isTransport) {
                item { OutlinedTextField(draft.start, { viewModel.update(draft.copy(start = it)) }, label = { Text(stringResource(R.string.start)) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) }
                item { TextButton(onClick = { viewModel.update(draft.copy(start = draft.end, end = draft.start)) }, enabled = enabled) { Text(stringResource(R.string.swap)) } }
                item { OutlinedTextField(draft.end, { viewModel.update(draft.copy(end = it)) }, label = { Text(stringResource(R.string.end)) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(draft.lines, { viewModel.update(draft.copy(lines = it)) }, label = { Text(stringResource(R.string.lines)) }, minLines = 2, enabled = enabled, modifier = Modifier.fillMaxWidth()) }
            }
            if (draft.isUtility) item { OutlinedTextField(draft.utilityNumber, { viewModel.update(draft.copy(utilityNumber = it)) }, label = { Text(stringResource(R.string.utility_number)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("bill_utility_number")) }
            item { OutlinedTextField(draft.note, { viewModel.update(draft.copy(note = it)) }, label = { Text(stringResource(R.string.note)) }, minLines = 3, enabled = enabled, modifier = Modifier.fillMaxWidth()) }
            if (state.preferences != null && enabled) item {
                Text(stringResource(R.string.receipt), style = MaterialTheme.typography.titleMedium)
                val locator = when (draft.imageEdit) {
                    BillImageEdit.CLEAR -> null
                    BillImageEdit.FILE -> draft.imageFile?.let { MediaLocator(draft = File(it)) }
                    BillImageEdit.ASSET -> MediaLocator(assetId = draft.imageAssetId)
                    BillImageEdit.KEEP -> if (draft.hasImage) MediaLocator(draft.imageAssetId, "bills", draft.imageSourceId) else null
                }
                PhotoInput(locator, { photo ->
                    viewModel.update(draft.copy(imageEdit = when { photo == null -> BillImageEdit.CLEAR; photo.assetId != null -> BillImageEdit.ASSET; else -> BillImageEdit.FILE },
                        imageAssetId = photo?.assetId, imageFile = photo?.content?.file?.path, hasImage = photo != null))
                }, viewModel.media, viewModel.processor, state.preferences)
            }
        }
    }
}

@Composable private fun AmountField(draft: BillDraft, field: AmountField, label: Int, enabled: Boolean, viewModel: BillsViewModel) {
    val value = when (field) { AmountField.ORIGINAL -> draft.amounts.original; AmountField.FINAL -> draft.amounts.final; AmountField.DISCOUNT -> draft.amounts.discount; AmountField.RATE -> draft.amounts.rate }
    OutlinedTextField(value, { viewModel.update(draft.copy(amounts = draft.amounts.edit(field, it, draft.type))) },
        label = { Text(stringResource(label)) }, singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag("amount_${field.name.lowercase()}"))
}
