package imehc.recoow.core.media

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.style.TextOverflow
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.domain.bills.BillRecord
import imehc.recoow.core.domain.media.MediaLocator
import imehc.recoow.core.domain.media.MediaRepository

/** 历史与统计明细共用核心账单行；金额由调用方按已有统计口径提供。 */
@Composable
fun RecordBillCoverRow(bill: BillRecord, repository: MediaRepository?, amount: String,
    onClick: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true, onLongClick: (() -> Unit)? = null) {
    val resources = LocalResources.current
    val tint = recordBillTint(bill.type)
    val icon = recordBillIcon(bill.type, bill.category)
    val thumbnail: @Composable () -> Unit = {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(RecoowLayout.iconSize))
    }
    val content: @Composable ColumnScope.() -> Unit = {
        Text(bill.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(recordDateTime(bill.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(recordBillMetadata(bill, resources), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    val action: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecoowMoneyValue(amount, bill.type)
            Box(Modifier.size(RecoowLayout.controlSize), contentAlignment = Alignment.Center) {
                if (trailing != null) trailing() else RecoowChevron()
            }
        }
    }
    if (repository != null) RecordCoverRow(locator = if (bill.hasImage) MediaLocator(bill.imageAssetId, "bills", bill.id) else null,
        repository = repository, icon = icon, tint = tint.copy(alpha = .12f), iconContent = thumbnail,
        onClick = onClick, modifier = modifier, trailing = action, enabled = enabled, onLongClick = onLongClick, content = content)
    else RecordThumbnailRow(onClick, modifier, thumbnail = thumbnail, trailing = action, enabled = enabled, onLongClick = onLongClick, content = content)
}
