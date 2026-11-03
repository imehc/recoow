package imehc.recoow.core.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 首帧占位：与真实行同尺寸的骨架，数据到位后不跳动。
 * 只有首屏空列表才用它；已有内容时的刷新仍走顶部进度条。
 */
@Composable
fun RecordSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 4,
    leading: Boolean = false,
    trailing: Boolean = true,
) {
    val shimmer = rememberSkeletonShimmer()
    val description = stringResource(R.string.record_loading)
    RecoowGroup(modifier.testTag("record_skeleton").semantics { contentDescription = description }) {
        repeat(rows) { index ->
            if (index > 0) RecoowDivider(if (leading) RecoowLayout.dividerInset else RecoowLayout.rowHorizontal)
            SkeletonRow(shimmer, leading, trailing, index)
        }
    }
}

/** 单条骨架：图标位 + 标题/副标题两根条 + 行尾取值条。 */
@Composable
private fun SkeletonRow(shimmer: Float, leading: Boolean, trailing: Boolean, index: Int) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = RecoowLayout.rowMinHeight)
            .padding(horizontal = RecoowLayout.rowHorizontal, vertical = RecoowLayout.rowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading) {
            SkeletonBlock(shimmer, Modifier.size(20.dp), CircleShape)
            Spacer(Modifier.width(RecoowLayout.iconTextGap))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // 标题条长度按行错开，避免读起来像表格线。
            SkeletonBlock(shimmer, Modifier.fillMaxWidth(TITLE_WIDTHS[index % TITLE_WIDTHS.size]).height(13.dp))
            SkeletonBlock(shimmer, Modifier.fillMaxWidth(SUBTITLE_WIDTHS[index % SUBTITLE_WIDTHS.size]).height(10.dp))
        }
        if (trailing) {
            Spacer(Modifier.width(12.dp))
            SkeletonBlock(shimmer, Modifier.width(46.dp).height(13.dp))
        }
    }
}

private val TITLE_WIDTHS = listOf(0.62f, 0.48f, 0.55f, 0.42f)
private val SUBTITLE_WIDTHS = listOf(0.34f, 0.28f, 0.40f, 0.24f)

@Composable
private fun SkeletonBlock(shimmer: Float, modifier: Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = shimmer)))
}

/** 一次呼吸式明暗变化，整屏骨架共用同一个动画，不做逐块流光。 */
@Composable
private fun rememberSkeletonShimmer(): Float {
    val transition = rememberInfiniteTransition(label = "record-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.24f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "record-skeleton-alpha",
    )
    return alpha
}

/**
 * 列表底部：追加、失败重试、到底提示三合一。
 * 追加中复用骨架，不再单独转圈；失败时给出重试而不是只印一行错误。
 */
@Composable
fun RecordListFooter(
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    failed: Boolean = false,
    hasMore: Boolean = false,
    endLabel: String? = null,
    onRetry: () -> Unit = {},
    onMore: () -> Unit = {},
) {
    when {
        failed -> RecoowGroup(modifier) {
            RecoowEmptyState(
                title = stringResource(R.string.record_failed),
                actionLabel = stringResource(R.string.record_retry),
                onAction = onRetry,
            )
        }
        loading -> RecordSkeleton(modifier.testTag("record_footer_loading"), rows = 2)
        hasMore -> RecoowGroup(modifier) {
            RecoowRow(title = stringResource(R.string.record_load_more), showChevron = true, onClick = onMore)
        }
        endLabel != null -> Text(
            endLabel,
            modifier = modifier.fillMaxWidth().padding(horizontal = RecoowLayout.rowHorizontal, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
