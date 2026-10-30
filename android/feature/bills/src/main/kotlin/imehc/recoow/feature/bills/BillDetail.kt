package imehc.recoow.feature.bills

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.RecoowPage
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.domain.media.ResolvedMedia
import imehc.recoow.core.domain.media.MediaAvailability
import imehc.recoow.core.media.ImagePreview
import imehc.recoow.core.media.MediaImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.catch

@Composable internal fun BillDetail(bill: BillRecord?, state: BillsState, viewModel: BillsViewModel, onBack: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val resources = LocalResources.current
    var preview by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var refunding by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    RecoowPage(stringResource(R.string.bill_details), navigationIcon = { BackButton(onBack) }) {
        if (bill == null) item { if (state.loading) CircularProgressIndicator() else Text(stringResource(R.string.missing_bill)) }
        else {
            item { Card { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bill.title, style = MaterialTheme.typography.titleLarge)
                Text((if (bill.type == BillType.INCOME) "+" else "−") + money(bill.amounts.final, locale), style = MaterialTheme.typography.headlineSmall)
                Text("${resources.getString(labelId(bill.type.wire))} · ${categoryLabel(resources, bill)}")
                Text(dateTime(bill.occurredAt, state.zone))
                Text(stringResource(when { bill.voided(state.now) -> R.string.refunded; bill.settlement == Settlement.REDEEMED -> R.string.redeemed; else -> R.string.active }))
                if (bill.expired(state.now)) Text(stringResource(R.string.expired_info), style = MaterialTheme.typography.bodySmall)
            } } }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { viewModel.edit(bill) }, enabled = !state.busy) { Text(stringResource(R.string.edit)) }
                TextButton(onClick = { viewModel.edit(bill, duplicate = true) }, enabled = !state.busy) { Text(stringResource(R.string.copy)) }
                if (bill.canRedeem(state.now)) TextButton(onClick = { viewModel.redeem(bill) }, enabled = !state.busy) { Text(stringResource(R.string.redeem)) }
                if (bill.canRefund(state.now)) TextButton(onClick = { refunding = true }, enabled = !state.busy) { Text(stringResource(R.string.refund_action)) }
                TextButton(onClick = { deleting = true }, enabled = !state.busy) { Text(stringResource(R.string.delete)) }
            } }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (bill.hasImage) item {
                val locator = MediaLocator(bill.imageAssetId, "bills", bill.id)
                val flow = remember(locator) { viewModel.media.observeContent(locator).catch { emit(ResolvedMedia(MediaAvailability.DAMAGED)) } }
                val resolved by flow.collectAsStateWithLifecycle(ResolvedMedia(MediaAvailability.PENDING))
                MediaImage(locator, viewModel.media, Modifier.fillMaxWidth().height(240.dp).clickable { preview = true })
                resolved.title?.let { Text(it) }
            }
            if (bill.type == BillType.EXPENSE) {
                item { DetailValue(stringResource(R.string.original), money(bill.amounts.original, locale)) }
                item { DetailValue(stringResource(R.string.discount), money(bill.amounts.discount, locale)) }
            }
            if (bill.type != BillType.STORED_VALUE_USE) item { DetailValue(stringResource(if (bill.type == BillType.INCOME) R.string.channel else R.string.payment), resources.getString(labelId(bill.payment.wire))) }
            bill.accountId?.let { id ->
                val account = state.accounts.firstOrNull { it.id == id }
                item { DetailValue(stringResource(if (bill.type == BillType.STORED_VALUE_USE) R.string.deduction_account else R.string.recharge_account), account?.name ?: id) }
                account?.let { item { DetailValue(stringResource(R.string.balance), money(it.balance, locale)) } }
            }
            bill.validUntil?.let { item { DetailValue(stringResource(R.string.expiry_date), dateTime(it, state.zone)) } }
            bill.redeemedAt?.let { item { DetailValue(stringResource(R.string.redeemed_at), dateTime(it, state.zone)) } }
            bill.refundReason?.let { item { DetailValue(stringResource(R.string.refund_reason), it) } }
            bill.startLocation?.let { item { DetailValue(stringResource(R.string.start), it) } }
            bill.endLocation?.let { item { DetailValue(stringResource(R.string.end), it) } }
            bill.transportLines?.let { item { DetailValue(stringResource(R.string.lines), it) } }
            bill.utilityAccountNumber?.let { number -> item {
                DetailValue(stringResource(R.string.utility_number), number)
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.utility_number), number)); copied = true
                }) { Text(stringResource(if (copied) R.string.copied else R.string.copy_number)) }
            } }
            bill.note?.let { item { DetailValue(stringResource(R.string.note), it) } }
        }
    }
    if (preview && bill != null) ImagePreview(listOf(MediaLocator(bill.imageAssetId, "bills", bill.id)), 0, viewModel.media) { preview = false }
    if (deleting && bill != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.delete_bill_question)) }, text = { Text(stringResource(R.string.delete_bill_info)) },
        confirmButton = { TextButton(onClick = { deleting = false; viewModel.delete(bill) }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } })
    if (refunding && bill != null) AlertDialog(onDismissRequest = { refunding = false }, title = { Text(stringResource(R.string.refund_action)) },
        text = { OutlinedTextField(reason, { reason = it }, label = { Text(stringResource(R.string.refund_reason)) }) },
        confirmButton = { TextButton(onClick = { refunding = false; viewModel.refund(bill, reason) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { refunding = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable internal fun DetailValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
