package imehc.recoow.core.designsystem

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** Refresh immediately on return and across midnight/time-zone changes while visible. */
@Composable
fun RecordRefreshEffect(intervalMillis: Long = 30_000, refresh: () -> Unit) {
    val current by rememberUpdatedState(refresh)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, intervalMillis) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { current(); delay(intervalMillis) } }
    }
}
