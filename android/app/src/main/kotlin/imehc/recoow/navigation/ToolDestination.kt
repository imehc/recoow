package imehc.recoow.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import imehc.recoow.R
import imehc.recoow.core.domain.navigation.ToolRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDestination(route: ToolRoute, onBack: () -> Unit, onHome: () -> Unit) {
    // Each feature replaces this unavailable destination when it is ready to accept deep links.
    val title = when (route) {
        ToolRoute.BILLS -> R.string.tool_bills
        ToolRoute.REMINDERS -> R.string.tool_reminders
        ToolRoute.DIARY -> R.string.tool_diary
        ToolRoute.FOOD -> R.string.tool_food
        ToolRoute.DECISION -> R.string.tool_decision
        ToolRoute.ITEMS -> R.string.tool_items
        ToolRoute.ANNIVERSARIES -> R.string.tool_anniversaries
        ToolRoute.LOCATION -> R.string.tool_location
    }
    Scaffold(
        modifier = Modifier.testTag("tool_${route.id}"),
        topBar = { TopAppBar(title = { Text(stringResource(title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.navigate_back)) }
        }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.tool_unavailable))
            Button(onClick = onHome, modifier = Modifier.testTag("return_home")) { Text(stringResource(R.string.return_home)) }
        }
    }
}
