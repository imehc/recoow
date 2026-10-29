package imehc.recoow

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import imehc.recoow.core.domain.preferences.AppLanguage
import imehc.recoow.core.domain.preferences.Appearance
import imehc.recoow.feature.home.HomeScreen
import imehc.recoow.feature.settings.SettingsScreen
import imehc.recoow.feature.settings.SettingsUiState
import imehc.recoow.feature.statistics.StatisticsScreen
import imehc.recoow.core.domain.navigation.ToolRoute
import imehc.recoow.navigation.ToolDestination

private enum class AppTab(val route: String, val label: Int, val icon: Int) {
    HOME("home", R.string.tab_home, R.drawable.ic_home),
    STATISTICS("statistics", R.string.tab_statistics, R.drawable.ic_statistics),
    SETTINGS("settings", R.string.tab_settings, R.drawable.ic_settings),
}

@Composable
fun RecoowApp(state: SettingsUiState, onAppearance: (Appearance) -> Unit, onLanguage: (AppLanguage) -> Unit,
    onRetry: () -> Unit, pendingTool: String? = null, onToolConsumed: (String) -> Unit = {}) {
    val controller = rememberNavController()
    val entry by controller.currentBackStackEntryAsState()
    LaunchedEffect(pendingTool) {
        pendingTool?.let { route ->
            if (ToolRoute.entries.any { it.id == route }) {
                controller.navigate("tool/$route") {
                    popUpTo(controller.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                }
            }
            onToolConsumed(route)
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (entry?.destination?.route?.startsWith("tool/") != true) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            modifier = Modifier.testTag("tab_${tab.route}"),
                            selected = entry?.destination?.route == tab.route,
                            onClick = {
                                controller.navigate(tab.route) {
                                    popUpTo(controller.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(controller, startDestination = AppTab.HOME.route, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(AppTab.HOME.route) { HomeScreen() }
            composable(AppTab.STATISTICS.route) { StatisticsScreen() }
            composable(AppTab.SETTINGS.route) { SettingsScreen(state, onAppearance, onLanguage, onRetry) }
            composable("tool/{toolId}") { destination ->
                val route = ToolRoute.entries.firstOrNull { it.id == destination.arguments?.getString("toolId") }
                if (route != null) ToolDestination(route,
                    onBack = { controller.popBackStack() },
                    onHome = { controller.popBackStack(AppTab.HOME.route, false) })
            }
        }
    }
}
