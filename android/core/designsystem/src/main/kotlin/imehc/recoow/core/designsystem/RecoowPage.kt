package imehc.recoow.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全应用唯一的页面骨架：紧凑顶栏 + 单列滚动内容。
 * Tab 根页、功能页与设置子页都走这里，不允许再手写第二套顶栏。
 * 组间距固定为 `RecoowLayout.sectionGap`，页面不再各自传间距。
 */
@Composable
fun RecoowPage(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    contentSpacing: Dp = RecoowLayout.sectionGap,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RecoowCompactTopBar(title, navigationIcon = navigationIcon, action = action) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
            content = content,
        )
    }
}
