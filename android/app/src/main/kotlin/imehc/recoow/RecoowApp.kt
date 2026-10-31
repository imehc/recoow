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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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
    onRetry: () -> Unit, pendingTool: String? = null, onToolConsumed: (String) -> Unit = {},
    onToolVisible: (String, Boolean) -> Unit = { _, _ -> },
    onBill: (String) -> Unit = {},
    onPickedPhotos: (Boolean) -> Unit = {}, onCameraPhotos: (Boolean) -> Unit = {},
    dataScreen: @Composable (() -> Unit) -> Unit = {},
    updateScreen: @Composable (() -> Unit) -> Unit = {},
    homeScreen: @Composable (() -> Unit, (String) -> Unit) -> Unit = { settings, tool -> HomeScreen(onSettings = settings, onTool = tool) },
    statisticsScreen: @Composable (() -> Unit, (ToolRoute, String?) -> Unit) -> Unit = { history, tool -> StatisticsScreen(onHistory = history, onTool = tool) },
    historyScreen: @Composable (() -> Unit, (ToolRoute, String) -> Unit) -> Unit = { _, _ -> },
    remindersScreen: @Composable (String?, () -> Unit) -> Unit = { _, _ -> },
    billsScreen: @Composable (() -> Unit) -> Unit = {},
    journalScreen: @Composable (ToolRoute, String?, () -> Unit, (ToolRoute, String) -> Unit) -> Unit = { _, _, _, _ -> },
    mediaScreen: @Composable (() -> Unit) -> Unit = {},
    syncScreen: @Composable (() -> Unit) -> Unit = {}) {
    val controller = rememberNavController()
    val entry by controller.currentBackStackEntryAsState()
    LaunchedEffect(pendingTool) {
        pendingTool?.let { route ->
            if (ToolRoute.entries.any { it.id == route.substringBefore("?") }) {
                controller.navigate("tool/$route") {
                    popUpTo(controller.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                }
            }
            onToolConsumed(route)
        }
    }
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (entry?.destination?.route?.startsWith("tool/") != true && entry?.destination?.route !in setOf("sync", "media", "history", "data", "update")) {
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
            composable(AppTab.HOME.route) { homeScreen({ controller.navigate("settings") { launchSingleTop = true } }) { tool -> controller.navigate("tool/$tool") { launchSingleTop = true } } }
            composable(AppTab.STATISTICS.route) { statisticsScreen({ controller.navigate("history") { launchSingleTop = true } }) { tool, id ->
                controller.navigate("tool/${tool.id}" + (id?.let { "?recordId=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""))
            } }
            composable("history") { historyScreen({ controller.popBackStack() }) { tool, id ->
                controller.navigate("tool/${tool.id}?recordId=${java.net.URLEncoder.encode(id, "UTF-8")}")
            } }
            composable(AppTab.SETTINGS.route) { SettingsScreen(state, onAppearance, onLanguage, onRetry,
                onSync = { controller.navigate("sync") { launchSingleTop = true } },
                onUpdate = { controller.navigate("update") { launchSingleTop = true } },
                onMedia = { controller.navigate("media") { launchSingleTop = true } }, onToolVisible = onToolVisible,
                onData = { controller.navigate("data") { launchSingleTop = true } }, onPickedPhotos = onPickedPhotos, onCameraPhotos = onCameraPhotos) }
            composable("data") { dataScreen { controller.popBackStack() } }
            composable("update") { updateScreen { controller.popBackStack() } }
            composable("media") { mediaScreen { controller.popBackStack() } }
            composable("sync") { syncScreen { controller.popBackStack() } }
            composable("tool/{toolId}?recordId={recordId}&delivery={delivery}", arguments = listOf(androidx.navigation.navArgument("recordId") { type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null }, androidx.navigation.navArgument("delivery") { type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null })) { destination ->
                val route = ToolRoute.entries.firstOrNull { it.id == destination.arguments?.getString("toolId") }
                if (route == ToolRoute.BILLS) {
                    LaunchedEffect(destination.arguments?.getString("recordId"), destination.arguments?.getString("delivery")) { destination.arguments?.getString("recordId")?.let(onBill) }
                    billsScreen { controller.popBackStack() }
                }
                else if (route == ToolRoute.REMINDERS) androidx.compose.runtime.key(destination.arguments?.getString("delivery")) { remindersScreen(destination.arguments?.getString("recordId")) { controller.popBackStack() } }
                else if (route in setOf(ToolRoute.DIARY, ToolRoute.FOOD, ToolRoute.ITEMS, ToolRoute.ANNIVERSARIES, ToolRoute.DECISION, ToolRoute.LOCATION)) androidx.compose.runtime.key(destination.arguments?.getString("delivery")) { journalScreen(requireNotNull(route), destination.arguments?.getString("recordId"), { controller.popBackStack() }) { tool, id ->
                    if (tool == ToolRoute.BILLS) onBill(id)
                    controller.navigate("tool/${tool.id}?recordId=${java.net.URLEncoder.encode(id, "UTF-8")}")
                } }
                else if (route != null) ToolDestination(route,
                    onBack = { controller.popBackStack() },
                    onHome = { controller.popBackStack(AppTab.HOME.route, false) })
            }
        }
    }
}
