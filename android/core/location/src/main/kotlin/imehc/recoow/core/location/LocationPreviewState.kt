package imehc.recoow.core.location

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect

/** 订阅由页面持有：离开页面、进入后台或开始记录时都会取消预览更新。 */
@Composable
fun rememberLocationPreview(enabled: Boolean = true, refreshKey: Int = 0): LocationPreviewState {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val preview = remember(context) {
        val source = AndroidLocationSource(context)
        LocationPreview(source, { source.lastKnownFix() })
    }
    var state by remember { mutableStateOf(LocationPreviewState()) }
    LaunchedEffect(preview, owner, enabled, refreshKey) {
        if (!enabled) { state = LocationPreviewState(); return@LaunchedEffect }
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            preview.states.collect { state = it }
        }
    }
    return state
}
