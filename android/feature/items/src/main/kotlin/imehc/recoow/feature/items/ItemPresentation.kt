package imehc.recoow.feature.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import imehc.recoow.core.designsystem.*
import imehc.recoow.core.designsystem.R as CommonR
import imehc.recoow.core.domain.items.StoredItem
import imehc.recoow.core.domain.records.NamedRecord
import imehc.recoow.core.media.RecordCoverRow

@Composable internal fun itemCategoryName(id: String?, categories: List<NamedRecord>): String =
    categories.firstOrNull { it.id == id }?.name ?: stringResource(if (id == null) R.string.items_uncategorized else R.string.items_deleted_category)

/** 与 iOS 同样使用物品图标与蓝色底板，记录图片继续优先。 */
@Composable internal fun ItemIconTile() {
    val tint = MaterialTheme.colorScheme.primary
    Box(Modifier.size(RecoowLayout.listCoverSize).clip(RoundedCornerShape(RecoowLayout.thumbnailRadius))
        .background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(painterResource(CommonR.drawable.ic_tool_items), null, tint = tint, modifier = Modifier.size(RecoowLayout.iconSize))
    }
}

@Composable internal fun ItemCategoryBadge(label: String) {
    RecordBadge(label, containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
}

@Composable internal fun ItemListRow(vm: ItemsViewModel, item: StoredItem, category: String) {
    RecordCoverRow(locator = if (item.hasImage) item.image else null, repository = vm.media,
        icon = CommonR.drawable.ic_tool_items, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        iconContent = { Icon(painterResource(CommonR.drawable.ic_tool_items), null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(RecoowLayout.iconSize)) },
        onClick = { vm.show(item.id) }, trailing = {
            Box(Modifier.size(RecoowLayout.controlSize), contentAlignment = Alignment.Center) { RecoowChevron() }
        }) {
        Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        ItemCategoryBadge(category)
    }
}
