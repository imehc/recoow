package imehc.recoow.feature.statistics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import imehc.recoow.core.designsystem.EmptyContent
import imehc.recoow.core.designsystem.RecoowPage

@Composable
fun StatisticsScreen() {
    RecoowPage(stringResource(R.string.statistics_title)) {
        item {
            EmptyContent(stringResource(R.string.statistics_empty), stringResource(R.string.statistics_empty_description), Modifier.fillMaxWidth())
        }
    }
}
