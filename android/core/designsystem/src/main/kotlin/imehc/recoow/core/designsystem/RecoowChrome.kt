package imehc.recoow.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import imehc.recoow.core.domain.navigation.ToolRoute

/**
 * 全应用共用的一套排布令牌：页面边距 16、组间距 22、组标题到卡片 8、
 * 行最小高 56、卡片圆角 14、图标 23。页面不重复定义局部位移或字号。
 */
object RecoowLayout {
    val pagePadding = 16.dp
    val sectionGap = 22.dp
    val headerGap = 8.dp
    val cardRadius = 14.dp
    val rowMinHeight = 56.dp
    val rowHorizontal = 14.dp
    val rowVertical = 9.dp
    val iconBox = 24.dp
    val iconSize = 23.dp
    val iconTextGap = 14.dp
    val dividerInset = 52.dp
    val contentMaxWidth = 640.dp
    val topBarMinHeight = 48.dp
    val tabBarMinHeight = 60.dp
}

/** 功能图标与语义色一一对应；页面不自己挑颜色。 */
object RecoowToolIcons {
    fun drawable(route: ToolRoute): Int = when (route) {
        ToolRoute.BILLS -> R.drawable.ic_tool_bills
        ToolRoute.REMINDERS -> R.drawable.ic_tool_reminders
        ToolRoute.DIARY -> R.drawable.ic_tool_diary
        ToolRoute.FOOD -> R.drawable.ic_tool_food
        ToolRoute.DECISION -> R.drawable.ic_tool_decision
        ToolRoute.ITEMS -> R.drawable.ic_tool_items
        ToolRoute.ANNIVERSARIES -> R.drawable.ic_tool_anniversaries
        ToolRoute.LOCATION -> R.drawable.ic_tool_tracks
    }

    @Composable
    fun tint(route: ToolRoute): Color {
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        return RecoowPalette.toolColor(route, dark)
    }
}

/**
 * 紧凑顶栏：全应用唯一的导航标题模式。标题 20sp 左对齐，次操作放右侧，
 * 系统状态栏安全区单独保留，内容高度随字体缩放增长。
 */
@Composable
fun RecoowCompactTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = RecoowLayout.topBarMinHeight)
            .padding(start = if (navigationIcon == null) RecoowLayout.pagePadding else 4.dp,
                end = if (action == null) RecoowLayout.pagePadding else 6.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigationIcon?.invoke()
        Text(
            title,
            modifier = Modifier.weight(1f).padding(start = if (navigationIcon == null) 0.dp else 4.dp),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) Row(verticalAlignment = Alignment.CenterVertically, content = action)
    }
}

/** 顶栏右侧的一条菜单动作：文字 + 可选的 testTag，收进「更多」入口。 */
data class RecoowAction(
    val label: String,
    val tag: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 顶栏右侧的「更多」入口：把「账户 / 分类 / 标签」这类次要操作收进下拉菜单，
 * 顶栏只留一个新建图标，避免按钮排成一排抢走标题。没有动作时整个入口不渲染。
 */
@Composable
fun RecoowOverflowMenu(actions: List<RecoowAction>, modifier: Modifier = Modifier, tag: String = "top_more") {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) {
            Icon(
                painterResource(R.drawable.ic_more),
                contentDescription = stringResource(R.string.record_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = DpOffset(x = 0.dp, y = 4.dp),
            shape = RoundedCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label, style = MaterialTheme.typography.bodyLarge) },
                    enabled = action.enabled,
                    onClick = { open = false; action.onClick() },
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = if (action.tag != null) Modifier.testTag(action.tag) else Modifier,
                )
            }
        }
    }
}

/** 搜索入口：点一下展开输入行，再点收起；低频搜索不占正文版面。 */
@Composable
fun RecoowSearchToggle(
    searching: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    label: String = stringResource(R.string.record_search),
) {
    IconButton(onClick = onToggle, modifier = if (tag != null) modifier.testTag(tag) else modifier) {
        Icon(
            painterResource(R.drawable.ic_search),
            contentDescription = label,
            tint = if (searching) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp),
        )
    }
}

/** 顶栏里唯一的新建入口：`+` 图标取代悬浮按钮。 */
@Composable
fun RecoowAddAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = if (tag != null) modifier.testTag(tag) else modifier,
    ) {
        Icon(
            painterResource(R.drawable.ic_plus),
            contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(23.dp),
        )
    }
}

/** 无胶囊指示器：选中态只用品牌色与 Tab 语义表达。 */
@Composable
fun RecoowTabBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = RecoowLayout.tabBarMinHeight).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
fun RowScope.RecoowTabItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    CompositionLocalProvider(LocalContentColor provides color) {
        Column(
            modifier.weight(1f)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .heightIn(min = RecoowLayout.tabBarMinHeight)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(RecoowLayout.iconSize), contentAlignment = Alignment.Center) { icon() }
            Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        }
    }
}

/** 分组标题：13sp、次级色、字距 0.02em；右侧可带一行元信息，或一个可点动作（品牌色）。 */
@Composable
fun RecoowSectionHeader(
    title: String,
    trailing: String? = null,
    modifier: Modifier = Modifier,
    onTrailingClick: (() -> Unit)? = null,
) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val padding = Modifier.padding(start = 4.dp, end = 4.dp, bottom = RecoowLayout.headerGap)
    val trailingColor = if (onTrailingClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val trailingStyle = if (onTrailingClick != null) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall
    if (largeText) {
        Column(modifier.fillMaxWidth().then(padding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (trailing != null) Text(
                trailing,
                modifier = if (onTrailingClick != null) Modifier.clickable(onClick = onTrailingClick) else Modifier,
                style = trailingStyle,
                color = trailingColor,
            )
        }
    } else {
        Row(modifier.fillMaxWidth().then(padding), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (trailing != null) Text(
                trailing,
                modifier = if (onTrailingClick != null) Modifier.clickable(onClick = onTrailingClick) else Modifier,
                style = trailingStyle,
                color = trailingColor,
                maxLines = 1,
            )
        }
    }
}

/** 分组卡片：白卡 + 1dp 发丝线，不用阴影。 */
@Composable
fun RecoowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RecoowLayout.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

/** 组内分隔线：默认与正文起点（14dp）对齐；有图标的行传 `RecoowLayout.dividerInset` 避开图标。 */
@Composable
fun RecoowDivider(inset: Dp = RecoowLayout.rowHorizontal) {
    HorizontalDivider(
        modifier = Modifier.padding(start = inset),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun RecoowChevron() {
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
    )
}

/**
 * 行式列表项：图标（可选）+ 标题 + 辅助信息 + 右侧值 / 箭头。
 * 只有跳转行显示箭头；`onClick == null` 时整行不可点。
 */
@Composable
fun RecoowRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingText: String? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = modifier.fillMaxWidth().heightIn(min = RecoowLayout.rowMinHeight)
    Row(
        if (onClick != null) base.clickable(onClick = onClick) else base,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal, vertical = RecoowLayout.rowVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Box(Modifier.size(RecoowLayout.iconBox), contentAlignment = Alignment.Center) { icon() }
                Spacer(Modifier.width(RecoowLayout.iconTextGap))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailingText != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    trailingText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                RecoowChevron()
            }
        }
    }
}

/** 功能入口行：图标走语义色，标题走正文色。 */
@Composable
fun RecoowToolRow(
    route: ToolRoute,
    title: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    RecoowRow(
        title = title,
        modifier = modifier,
        subtitle = status,
        subtitleColor = statusColor,
        showChevron = true,
        onClick = onClick,
        icon = {
            Icon(
                painterResource(RecoowToolIcons.drawable(route)),
                contentDescription = null,
                tint = RecoowToolIcons.tint(route),
                modifier = Modifier.size(RecoowLayout.iconSize),
            )
        },
    )
}

/** 开关行：整行可点，等价于系统 Switch 语义；不显示跳转箭头。 */
@Composable
fun RecoowSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = RecoowLayout.rowMinHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = RecoowLayout.rowHorizontal, vertical = RecoowLayout.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(RecoowLayout.iconBox), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(RecoowLayout.iconTextGap))
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** 概览指标：名称在上、数值在下；默认两列，大字号自动退化为单列。 */
data class RecoowMetric(
    val label: String,
    val value: String,
    val tag: String? = null,
    val emphasis: Boolean = true,
    val onClick: (() -> Unit)? = null,
)

@Composable
fun RecoowMetricGrid(metrics: List<RecoowMetric>, modifier: Modifier = Modifier, columns: Int = 2) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val cols = if (largeText) 1 else columns
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth()) {
        metrics.chunked(cols).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) HorizontalDivider(thickness = 1.dp, color = line)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { columnIndex, metric ->
                    if (columnIndex > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(line))
                    val cell = Modifier.weight(1f)
                        .then(if (metric.tag != null) Modifier.testTag(metric.tag) else Modifier)
                        .then(if (metric.onClick != null) Modifier.clickable(onClick = metric.onClick) else Modifier)
                        .heightIn(min = 74.dp)
                        .padding(horizontal = 14.dp, vertical = 13.dp)
                    Column(cell) {
                        Text(metric.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            metric.value,
                            modifier = Modifier.padding(top = 3.dp),
                            style = if (metric.emphasis) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 空状态：与列表行同样左对齐，标题正文一档，不用居中大标题冒充按钮。
 * 可选图标与主操作。
 */
@Composable
fun RecoowEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: Int? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal, vertical = 18.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(RecoowLayout.iconSize),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        if (description != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                actionLabel,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(onClick = onAction)
                    .padding(vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 状态提示：权限未开、提醒失效这类需要解释又要给动作的场合。
 * 与分组卡片同一种容器，不用另一套灰底卡片。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecoowNotice(text: String, modifier: Modifier = Modifier, actions: List<RecoowAction> = emptyList()) {
    RecoowGroup(modifier) {
        Column(Modifier.padding(horizontal = RecoowLayout.rowHorizontal, vertical = 13.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    actions.forEach { action ->
                        Text(
                            action.label,
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = action.enabled, onClick = action.onClick)
                                .padding(vertical = 10.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (action.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
