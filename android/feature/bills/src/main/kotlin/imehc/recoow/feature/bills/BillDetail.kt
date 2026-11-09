package imehc.recoow.feature.bills

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.domain.bills.*
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.domain.media.ResolvedMedia
import imehc.recoow.core.domain.media.MediaAvailability
import imehc.recoow.core.media.ImagePreview
import imehc.recoow.core.media.MediaImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.catch

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun BillDetail(bill: BillRecord?, state: BillsState, viewModel: BillsViewModel, onBack: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val resources = LocalResources.current
    var preview by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var refunding by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    // 操作不占正文：顶栏「⋯」与长按摘要卡共用同一组动作。
    val menuActions: List<Pair<String, (() -> Unit)>> = bill?.let { b -> listOfNotNull(
        stringResource(R.string.edit) to { viewModel.edit(b) },
        stringResource(R.string.copy) to { viewModel.edit(b, duplicate = true) },
        if (b.canRedeem(state.now)) stringResource(R.string.redeem) to { viewModel.redeem(b) } else null,
        if (b.canRefund(state.now)) stringResource(R.string.refund_action) to { refunding = true } else null,
        b.utilityAccountNumber?.let { number ->
            stringResource(if (copied) R.string.copied else R.string.copy_number) to {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.utility_number), number))
                copied = true
            }
        },
        stringResource(R.string.delete) to { deleting = true },
    ) } ?: emptyList()
    val deleteLabel = stringResource(R.string.delete)
    RecoowPage(stringResource(R.string.bill_details), navigationIcon = { BackButton(onBack) }, contentSpacing = RecoowLayout.sectionGap,
        action = { if (bill != null && menuActions.isNotEmpty()) RecoowOverflowMenu(menuActions.map { (label, onClick) -> RecoowAction(label, enabled = !state.busy, onClick = onClick) }, tag = "bill_detail_more") }) {
        if (bill == null) item(key = "missing") {
            RecoowGroup {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else RecoowEmptyState(stringResource(R.string.missing_bill))
            }
        } else {
            item(key = "head") {
                Box {
                    RecoowGroup(Modifier.combinedClickable(onClick = {}, onLongClick = { if (!state.busy) menu = true })) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                                Text(bill.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 3)
                                Text(
                                    stringResource(when { bill.voided(state.now) -> R.string.refunded; bill.settlement == Settlement.REDEEMED -> R.string.redeemed; else -> R.string.active }),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                (if (bill.type == BillType.INCOME) "+" else "−") + money(bill.amounts.final, locale),
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.titleLarge,
                                color = if (bill.type == BillType.INCOME) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "${resources.getString(labelId(bill.type.wire))} · ${categoryLabel(resources, bill)} · ${dateTime(bill.occurredAt, state.zone)}",
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (bill.expired(state.now)) Text(
                                stringResource(R.string.expired_info),
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    DropdownMenu(menu, { menu = false }, shape = MaterialTheme.shapes.medium,
                        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        menuActions.forEach { (label, onClick) ->
                            DropdownMenuItem(text = { Text(label, color = if (label == deleteLabel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                                enabled = !state.busy, onClick = { menu = false; onClick() })
                        }
                    }
                }
            }
            if (state.busy) item(key = "busy") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (bill.hasImage) item(key = "image") {
                val locator = MediaLocator(bill.imageAssetId, "bills", bill.id)
                val flow = remember(locator) { viewModel.media.observeContent(locator).catch { emit(ResolvedMedia(MediaAvailability.DAMAGED)) } }
                val resolved by flow.collectAsStateWithLifecycle(ResolvedMedia(MediaAvailability.PENDING))
                Column {
                    MediaImage(
                        locator, viewModel.media,
                        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(RecoowLayout.cardRadius)).clickable { preview = true },
                    )
                    resolved.title?.let { Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            item(key = "details") {
                val account = bill.accountId?.let { id -> state.accounts.firstOrNull { it.id == id } }
                val details = listOfNotNull(
                    if (bill.type == BillType.EXPENSE) stringResource(R.string.original) to money(bill.amounts.original, locale) else null,
                    if (bill.type == BillType.EXPENSE) stringResource(R.string.discount) to money(bill.amounts.discount, locale) else null,
                    if (bill.type != BillType.STORED_VALUE_USE) {
                        stringResource(if (bill.type == BillType.INCOME) R.string.channel else R.string.payment) to resources.getString(labelId(bill.payment.wire))
                    } else null,
                    bill.accountId?.let { id ->
                        stringResource(if (bill.type == BillType.STORED_VALUE_USE) R.string.deduction_account else R.string.recharge_account) to (account?.name ?: id)
                    },
                    account?.let { stringResource(R.string.balance) to money(it.balance, locale) },
                    bill.validUntil?.let { stringResource(R.string.expiry_date) to dateTime(it, state.zone) },
                    bill.redeemedAt?.let { stringResource(R.string.redeemed_at) to dateTime(it, state.zone) },
                    bill.refundReason?.let { stringResource(R.string.refund_reason) to it },
                    bill.startLocation?.let { stringResource(R.string.start) to it },
                    bill.endLocation?.let { stringResource(R.string.end) to it },
                    bill.transportLines?.let { stringResource(R.string.lines) to it },
                    bill.utilityAccountNumber?.let { stringResource(R.string.utility_number) to it },
                )
                if (details.isNotEmpty()) Column {
                    RecoowSectionHeader(stringResource(R.string.bills_detail_section))
                    RecoowGroup {
                        details.forEachIndexed { index, (label, value) ->
                            if (label == stringResource(R.string.utility_number)) {
                                RecoowCopyableValueRow(label, value, stringResource(R.string.copy_number), stringResource(R.string.copied))
                            } else RecoowRow(title = label, trailingText = value)
                            if (index < details.lastIndex) RecoowDivider()
                        }
                    }
                }
            }
            bill.note?.takeIf { it.isNotBlank() }?.let { note -> item(key = "note") {
                Column {
                    RecoowSectionHeader(stringResource(R.string.note))
                    RecoowGroup {
                        Text(note, modifier = Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            } }
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
    RecoowRow(title = label, trailingText = value)
}
