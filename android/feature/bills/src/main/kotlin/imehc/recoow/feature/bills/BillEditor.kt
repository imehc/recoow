package imehc.recoow.feature.bills

import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
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
    var attempted by rememberSaveable { mutableStateOf(false) }
    val titleError = if (attempted && draft.title.isBlank()) stringResource(CommonR.string.record_field_required) else null
    val amountMissing = draft.amounts.values(draft.type) == null
    val originalMissing = draft.type == BillType.EXPENSE && (BillMoney.cents(draft.amounts.original) ?: 0L) <= 0L
    val finalMissing = draft.type != BillType.EXPENSE && (BillMoney.cents(draft.amounts.final) ?: 0L) <= 0L
    val amountError = when {
        !attempted || !amountMissing -> null
        originalMissing -> stringResource(R.string.amount_original_required)
        finalMissing -> stringResource(CommonR.string.record_field_required)
        else -> stringResource(R.string.amount_invalid)
    }
    val accountError = if (attempted && draft.requiresAccount && draft.accountId == null) stringResource(CommonR.string.record_field_choose) else null
    val validUntilError = if (attempted && draft.isGroupBuy && draft.validUntil == null) stringResource(CommonR.string.record_field_choose) else null
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        RecoowCompactTopBar(title = stringResource(if (draft.revision == null) R.string.add_bill else R.string.edit_bill),
            navigationIcon = { BackButton(onBack) }, action = {
                IconButton(enabled = enabled, onClick = { if (draft.valid) viewModel.save() else attempted = true },
                    modifier = Modifier.testTag("bill_save")) {
                    Icon(painterResource(CommonR.drawable.ic_check), stringResource(R.string.save), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding().testTag("bill_form"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(RecoowLayout.compactSectionGap),
        ) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                RecordFormSection(stringResource(R.string.bill_basic_section)) {
                    RecordFormText(stringResource(R.string.title), draft.title, { viewModel.update(draft.copy(title = it)) },
                        enabled = enabled, placeholder = "", error = titleError, tag = "bill_title")
                    RecordFormDivider()
                    RecordChoice(stringResource(R.string.bill_type), draft.type,
                        BillType.entries.map { it to resources.getString(labelId(it.wire)) },
                        { viewModel.update(draft.withType(it)) }, enabled = enabled, bordered = false,
                        modifier = Modifier.testTag("bill_type"))
                    RecordFormDivider()
                    RecordChoice(stringResource(R.string.category), draft.category,
                        (if (draft.type == BillType.INCOME) incomeCategories else expenseCategories).map {
                            it to resources.getString(if (draft.type == BillType.INCOME && it == "other") R.string.other_income else labelId(it))
                        }, { viewModel.update(draft.copy(category = it)) }, enabled = enabled, bordered = false,
                        modifier = Modifier.testTag("bill_category"))
                    if (draft.type != BillType.STORED_VALUE_USE) {
                        RecordFormDivider()
                        RecordChoice(stringResource(if (draft.type == BillType.INCOME) R.string.channel else R.string.payment), draft.payment,
                            PaymentMethod.entries.map { it to resources.getString(labelId(it.wire)) },
                            { viewModel.update(draft.copy(payment = it)) }, enabled = enabled, bordered = false)
                    }
                    RecordFormDivider()
                    RecordFormDateTime(stringResource(R.string.date), draft.occurredAt, { viewModel.update(draft.copy(occurredAt = it)) }, enabled)
                    if (draft.isGroupBuy) {
                        RecordFormDivider()
                        RecordFormDateTime(stringResource(R.string.valid_until), draft.validUntil ?: 0L,
                            { viewModel.update(draft.copy(validUntil = it)) }, enabled)
                        validUntilError?.let { message -> RecordFormDivider(); RecordFormHint(message) }
                    }
                }
            }
            item {
                RecordFormSection(stringResource(R.string.bill_amount_section)) {
                    if (draft.type == BillType.EXPENSE) {
                        AmountRow(draft, AmountField.ORIGINAL, R.string.original, enabled, viewModel, amountError.takeIf { originalMissing })
                        RecordFormDivider()
                    }
                    AmountRow(draft, AmountField.FINAL, when (draft.type) {
                        BillType.EXPENSE -> R.string.final_amount; BillType.INCOME -> R.string.amount; else -> R.string.deduction
                    }, enabled, viewModel, amountError.takeIf { draft.type != BillType.EXPENSE && finalMissing })
                    if (draft.type == BillType.EXPENSE) {
                        RecordFormDivider()
                        AmountRow(draft, AmountField.DISCOUNT, R.string.discount, enabled, viewModel)
                        RecordFormDivider()
                        AmountRow(draft, AmountField.RATE, R.string.rate, enabled, viewModel)
                    }
                    if (amountError != null && !originalMissing && !finalMissing) {
                        RecordFormDivider()
                        RecordFormHint(amountError)
                    }
                }
            }
            if (draft.type != BillType.INCOME) item {
                RecordFormSection(stringResource(R.string.bill_account_section)) {
                    if (draft.type == BillType.EXPENSE) {
                        RecordSwitch(stringResource(R.string.recharge), draft.recharge, { viewModel.update(draft.copy(recharge = it)) },
                            enabled = enabled, modifier = Modifier.padding(horizontal = 14.dp).testTag("bill_recharge"))
                        if (draft.requiresAccount) RecordFormDivider()
                    }
                    if (draft.requiresAccount) {
                        RecordChoice(stringResource(R.string.select_account), draft.accountId, state.accounts.map { it.id as String? to it.name },
                            { viewModel.update(draft.copy(accountId = it)) }, enabled = enabled, bordered = false, error = accountError)
                        state.accounts.firstOrNull { it.id == draft.accountId }?.let { account ->
                            RecordFormDivider()
                            RecordFormValue(stringResource(R.string.balance), money(account.balance, locale))
                        }
                        RecordFormDivider()
                        RecordFormAction(stringResource(R.string.add_account), onClick = { viewModel.editAccount() },
                            enabled = enabled, tag = "bill_add_account")
                    }
                    if (draft.requiresAccount) {
                        RecordFormDivider()
                        RecordFormHint(stringResource(R.string.recharge_hint))
                    }
                }
            }
            if (draft.isTransport) item {
                RecordFormSection(stringResource(R.string.bill_transport_section)) {
                    RecordFormSwap(stringResource(R.string.swap), onClick = {
                        viewModel.update(draft.copy(start = draft.end, end = draft.start))
                    }, enabled = enabled, modifier = Modifier.testTag("bill_swap_locations"),
                        first = {
                            RecordFormText(stringResource(R.string.start), draft.start,
                                { viewModel.update(draft.copy(start = it)) }, enabled = enabled)
                        }, second = {
                            RecordFormText(stringResource(R.string.end), draft.end,
                                { viewModel.update(draft.copy(end = it)) }, enabled = enabled)
                        })
                    RecordFormDivider()
                    RecordFormText(stringResource(R.string.lines), draft.lines, { viewModel.update(draft.copy(lines = it)) }, enabled = enabled, placeholder = "", multiline = true)
                }
            }
            if (draft.isUtility) item {
                RecordFormSection(stringResource(R.string.utility)) {
                    RecordFormText(stringResource(R.string.utility_number), draft.utilityNumber,
                        { viewModel.update(draft.copy(utilityNumber = it)) }, enabled = enabled, tag = "bill_utility_number")
                }
            }
            item {
                RecordForm {
                    RecordFormText(stringResource(R.string.note), draft.note, { viewModel.update(draft.copy(note = it)) },
                        enabled = enabled, placeholder = "", multiline = true)
                }
            }
            if (state.preferences != null && enabled) item {
                Column {
                    val locator = when (draft.imageEdit) {
                        BillImageEdit.CLEAR -> null
                        BillImageEdit.FILE -> draft.imageFile?.let { MediaLocator(draft = File(it)) }
                        BillImageEdit.ASSET -> MediaLocator(assetId = draft.imageAssetId)
                        BillImageEdit.KEEP -> if (draft.hasImage) MediaLocator(draft.imageAssetId, "bills", draft.imageSourceId) else null
                    }
                    PhotoInput(locator, { photo ->
                        viewModel.update(draft.copy(imageEdit = when { photo == null -> BillImageEdit.CLEAR; photo.assetId != null -> BillImageEdit.ASSET; else -> BillImageEdit.FILE },
                            imageAssetId = photo?.assetId, imageFile = photo?.content?.file?.path, hasImage = photo != null))
                    }, viewModel.media, viewModel.processor, state.preferences, compact = true)
                }
            }
        }
    }
}

@Composable private fun AmountRow(draft: BillDraft, field: AmountField, label: Int, enabled: Boolean, viewModel: BillsViewModel, error: String? = null) {
    val value = when (field) {
        AmountField.ORIGINAL -> draft.amounts.original
        AmountField.FINAL -> draft.amounts.final
        AmountField.DISCOUNT -> draft.amounts.discount
        AmountField.RATE -> draft.amounts.rate
    }
    RecordFormText(stringResource(label), value, { viewModel.update(draft.copy(amounts = draft.amounts.edit(field, it, draft.type))) },
        enabled = enabled, keyboard = KeyboardType.Decimal, error = error, tag = "amount_${field.name.lowercase()}")
}
