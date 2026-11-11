package imehc.recoow

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import imehc.recoow.core.designsystem.LocalRecordReturnToSource
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
import imehc.recoow.core.designsystem.RecoowTabBar
import imehc.recoow.core.designsystem.RecoowTabItem
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
    onPickedPhotos: (Boolean) -> Unit = {}, onCameraPhotos: (Boolean) -> Unit = {},
    dataScreen: @Composable (() -> Unit, () -> Unit, () -> Unit) -> Unit = { _, _, _ -> },
    updateScreen: @Composable (() -> Unit) -> Unit = {},
    homeScreen: @Composable (() -> Unit, (String) -> Unit) -> Unit = { settings, tool -> HomeScreen(onSettings = settings, onTool = tool) },
    statisticsScreen: @Composable (() -> Unit, (ToolRoute, String?) -> Unit) -> Unit = { history, tool -> StatisticsScreen(onHistory = history, onTool = tool) },
    historyScreen: @Composable (() -> Unit, (ToolRoute, String) -> Unit) -> Unit = { _, _ -> },
    remindersScreen: @Composable (String?, () -> Unit) -> Unit = { _, _ -> },
    billsScreen: @Composable (String?, () -> Unit) -> Unit = { _, _ -> },
    journalScreen: @Composable (ToolRoute, String?, () -> Unit, (ToolRoute, String) -> Unit) -> Unit = { _, _, _, _ -> },
    mediaScreen: @Composable (() -> Unit) -> Unit = {},
    accountScreen: @Composable (() -> Unit, () -> Unit, () -> Unit) -> Unit = { _, _, _ -> },
    syncLoginScreen: @Composable (() -> Unit) -> Unit = {},
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
            if (entry?.destination?.route?.startsWith("tool/") != true && entry?.destination?.route !in setOf("sync", "sync-login", "account", "media", "history", "data", "update", "display", "home-tools", "photos")) {
                RecoowTabBar {
                    AppTab.entries.forEach { tab ->
                        RecoowTabItem(
                            label = stringResource(tab.label),
                            selected = entry?.destination?.route == tab.route,
                            onClick = {
                                controller.navigate(tab.route) {
                                    popUpTo(controller.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            modifier = Modifier.testTag("tab_${tab.route}"),
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(controller, startDestination = AppTab.HOME.route, modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable(AppTab.HOME.route) { homeScreen({ controller.navigate("settings") { launchSingleTop = true } }) { tool -> controller.navigate("tool/$tool") { launchSingleTop = true } } }
            composable(AppTab.STATISTICS.route) { statisticsScreen({ controller.navigate("history") { launchSingleTop = true } }) { tool, id ->
                controller.navigate(if (id != null) recordDestination(tool, id) else "tool/${tool.id}")
            } }
            composable("history") { historyScreen({ controller.popBackStack() }) { tool, id ->
                controller.navigate(recordDestination(tool, id))
            } }
            composable(AppTab.SETTINGS.route) { SettingsScreen(state, onAppearance, onLanguage, onRetry,
                onAccount = { controller.navigate("account") { launchSingleTop = true } },
                onSync = { controller.navigate("sync") { launchSingleTop = true } },
                onUpdate = { controller.navigate("update") { launchSingleTop = true } },
                onMedia = { controller.navigate("media") { launchSingleTop = true } }, onToolVisible = onToolVisible,
                onData = { controller.navigate("data") { launchSingleTop = true } }, onPickedPhotos = onPickedPhotos, onCameraPhotos = onCameraPhotos,
                onDisplay = { controller.navigate("display") { launchSingleTop = true } },
                onHomeTools = { controller.navigate("home-tools") { launchSingleTop = true } }) }
            composable("data") { dataScreen({ controller.popBackStack() },
                { controller.navigate("media") { launchSingleTop = true } }, { controller.navigate("photos") { launchSingleTop = true } }) }
            composable("display") { imehc.recoow.feature.settings.DisplaySettingsScreen(state, { controller.popBackStack() }, onAppearance, onLanguage, onRetry) }
            composable("home-tools") { imehc.recoow.feature.settings.HomeToolsSettingsScreen(state, { controller.popBackStack() }, onToolVisible, onRetry) }
            composable("photos") { imehc.recoow.feature.settings.PhotoSettingsScreen(state, { controller.popBackStack() }, onPickedPhotos, onCameraPhotos, onRetry) }
            composable("update") { updateScreen { controller.popBackStack() } }
            composable("media") { mediaScreen { controller.popBackStack() } }
            composable("sync") { syncScreen { controller.popBackStack() } }
            composable("sync-login") { syncLoginScreen { controller.popBackStack() } }
            composable("account") { accountScreen({ controller.popBackStack() },
                { controller.navigate("sync-login") { launchSingleTop = true } },
                { controller.navigate("sync") { launchSingleTop = true } }) }
            composable("tool/{toolId}?recordId={recordId}&delivery={delivery}&returnToSource={returnToSource}", arguments = listOf(androidx.navigation.navArgument("returnToSource") { type = androidx.navigation.NavType.BoolType; defaultValue = false }, androidx.navigation.navArgument("recordId") { type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null }, androidx.navigation.navArgument("delivery") { type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null })) { destination ->
                val route = ToolRoute.entries.firstOrNull { it.id == destination.arguments?.getString("toolId") }
                CompositionLocalProvider(LocalRecordReturnToSource provides (destination.arguments?.getBoolean("returnToSource") == true)) {
                    if (route == ToolRoute.BILLS) {
                        androidx.compose.runtime.key(destination.arguments?.getString("delivery")) {
                            billsScreen(destination.arguments?.getString("recordId")) { controller.popBackStack() }
                        }
                    }
                    else if (route == ToolRoute.REMINDERS) androidx.compose.runtime.key(destination.arguments?.getString("delivery")) { remindersScreen(destination.arguments?.getString("recordId")) { controller.popBackStack() } }
                    else if (route in setOf(ToolRoute.DIARY, ToolRoute.FOOD, ToolRoute.ITEMS, ToolRoute.ANNIVERSARIES, ToolRoute.DECISION, ToolRoute.LOCATION)) androidx.compose.runtime.key(destination.arguments?.getString("delivery")) { journalScreen(requireNotNull(route), destination.arguments?.getString("recordId"), { controller.popBackStack() }) { tool, id ->
                        controller.navigate(recordDestination(tool, id))
                    } }
                    else if (route != null) ToolDestination(route,
                        onBack = { controller.popBackStack() },
                        onHome = { controller.popBackStack(AppTab.HOME.route, false) })
                }
            }
        }
    }
}

/** 应用内记录跳转保留来源；外部深链仍沿用原有返回层级。 */
private fun recordDestination(tool: ToolRoute, id: String) =
    "tool/${tool.id}?recordId=${java.net.URLEncoder.encode(id, "UTF-8")}&returnToSource=true"
