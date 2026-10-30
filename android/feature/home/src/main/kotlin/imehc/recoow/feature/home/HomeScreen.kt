package imehc.recoow.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.core.designsystem.RecoowPage

@Composable
fun HomeScreen(onBills: () -> Unit = {}) {
    RecoowPage(stringResource(R.string.home_title)) {
        item {
            Card(onClick = onBills, modifier = Modifier.fillMaxWidth().testTag("home_bills")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.home_bills), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.home_bills_description), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
