package imehc.recoow.feature.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import imehc.recoow.core.designsystem.EmptyContent
import imehc.recoow.core.designsystem.RecoowPage

@Composable
fun HomeScreen() {
    RecoowPage(stringResource(R.string.home_title)) {
        item {
            EmptyContent(stringResource(R.string.home_empty), stringResource(R.string.home_empty_description), Modifier.fillMaxWidth())
        }
    }
}
