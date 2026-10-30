package imehc.recoow.core.media

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import imehc.recoow.core.domain.media.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
fun MediaImage(locator: MediaLocator, repository: MediaRepository, modifier: Modifier = Modifier,
    description: String? = null, fullSize: Boolean = false, contentScale: ContentScale = ContentScale.Crop) {
    val flow = remember(repository, locator) { repository.observeContent(locator).catch { emit(ResolvedMedia(MediaAvailability.DAMAGED)) } }
    val media by flow.collectAsStateWithLifecycle(ResolvedMedia(MediaAvailability.PENDING))
    Box(modifier, contentAlignment = Alignment.Center) {
        if (media.availability == MediaAvailability.READY && media.file != null) {
            val context = LocalContext.current
            val size = if (fullSize) 2048 else 512
            val request = remember(media.file, media.cacheKey, size) {
                ImageRequest.Builder(context).data(media.file).size(size)
                    .memoryCacheKey("${media.cacheKey ?: media.file?.path}:$size").build()
            }
            var failed by remember(request) { mutableStateOf(false) }
            AsyncImage(request, description ?: media.title, Modifier.fillMaxSize(), contentScale = contentScale,
                onError = { failed = true }, onSuccess = { failed = false })
            if (failed) Text(stringResource(R.string.damaged), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        } else Text(stringResource(when (media.availability) {
            MediaAvailability.DAMAGED -> R.string.damaged
            MediaAvailability.MISSING -> R.string.missing
            else -> R.string.pending
        }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreview(images: List<MediaLocator>, initialPage: Int, repository: MediaRepository, onClose: () -> Unit) {
    if (images.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val pager = rememberPagerState(initialPage.coerceIn(images.indices)) { images.size }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text(stringResource(R.string.page, pager.currentPage + 1, images.size)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } })
        }, bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(enabled = pager.currentPage > 0, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text(stringResource(R.string.previous)) }
                TextButton(enabled = pager.currentPage < images.lastIndex, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Text(stringResource(R.string.next)) }
            }
        }) { padding ->
            HorizontalPager(pager, Modifier.fillMaxSize().padding(padding), userScrollEnabled = !zoomed,
                key = { index -> images[index].let { image -> "${image.recordId}:${image.assetId}:${image.draft?.path}:$index" } }) { index ->
                var scale by rememberSaveable { mutableFloatStateOf(1f) }
                var x by rememberSaveable { mutableFloatStateOf(0f) }
                var y by rememberSaveable { mutableFloatStateOf(0f) }
                LaunchedEffect(pager.currentPage, scale) { if (pager.currentPage == index) zoomed = scale > 1f }
                BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
                    val bounds = constraints
                    val transform = rememberTransformableState { zoom, pan, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        x = (x + pan.x).coerceIn(-bounds.maxWidth * (scale - 1) / 2f, bounds.maxWidth * (scale - 1) / 2f)
                        y = (y + pan.y).coerceIn(-bounds.maxHeight * (scale - 1) / 2f, bounds.maxHeight * (scale - 1) / 2f)
                    }
                    MediaImage(images[index], repository, Modifier.fillMaxSize()
                        .transformable(transform, canPan = { scale > 1f })
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = x; translationY = y },
                        fullSize = true, contentScale = ContentScale.Fit)
                    TextButton(onClick = { scale = 1f; x = 0f; y = 0f }, modifier = Modifier.align(Alignment.BottomEnd)) { Text(stringResource(R.string.reset)) }
                }
            }
        }
    }
}
