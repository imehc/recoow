import Combine
import SwiftUI

/// 应用根导航。后续新增工具时，在 ToolRoute 中追加入口即可继承主页、设置和详情隐藏 Tab 的规则。
enum AppLaunchDestination: Hashable {
    case home
    case dataSettings
}

struct AppRoot: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedTab: AppTab = .home
    @State private var homePath = NavigationPath()
    @State private var statisticsPath = NavigationPath()
    @State private var settingsPath = NavigationPath()
    @State private var homeTabBarVisibility: Visibility = .visible
    @State private var statisticsTabBarVisibility: Visibility = .visible
    @State private var settingsTabBarVisibility: Visibility = .visible
    let resetAllLocalData: (AppLaunchDestination) async throws -> Void

    init(
        initialDestination: AppLaunchDestination = .home,
        resetAllLocalData: @escaping (AppLaunchDestination) async throws -> Void = { _ in }
    ) {
        var initialSettingsPath = NavigationPath()
        if initialDestination == .dataSettings {
            initialSettingsPath.append(SettingsNavigationRoute.data)
        }

        _selectedTab = State(initialValue: initialDestination == .dataSettings ? .settings : .home)
        _settingsPath = State(initialValue: initialSettingsPath)
        self.resetAllLocalData = resetAllLocalData
    }

    var body: some View {
        let language = container.appPreferences.language

        TabView(selection: $selectedTab) {
            Tab(AppLocalization.string("首页", language: language), systemImage: "house", value: .home) {
                NavigationStack(path: $homePath) {
                    HomeView(tabBarVisibility: $homeTabBarVisibility) {
                        selectedTab = .settings
                    }
                }
                .toolbar(rootTabBarVisibility(path: homePath, requested: homeTabBarVisibility), for: .tabBar)
            }

            Tab(AppLocalization.string("统计", language: language), systemImage: "chart.bar.xaxis", value: .statistics) {
                NavigationStack(path: $statisticsPath) {
                    StatisticsView(tabBarVisibility: $statisticsTabBarVisibility)
                }
                .toolbar(rootTabBarVisibility(path: statisticsPath, requested: statisticsTabBarVisibility), for: .tabBar)
            }

            Tab(AppLocalization.string("设置", language: language), systemImage: "gearshape", value: .settings) {
                NavigationStack(path: $settingsPath) {
                    SettingsView(
                        tabBarVisibility: $settingsTabBarVisibility,
                        resetAllLocalData: {
                            try await resetAllLocalData(.dataSettings)
                        }
                    )
                }
                .toolbar(rootTabBarVisibility(path: settingsPath, requested: settingsTabBarVisibility), for: .tabBar)
            }
        }
        .environment(\.locale, language.locale)
        .preferredColorScheme(container.appPreferences.colorScheme)
        .onOpenURL { url in
            handleDeepLink(url)
        }
        .task {
            await container.notificationScheduler.clearBadge()
            await container.locationTrackerViewModel.pauseInterruptedRecordingIfNeeded()
        }
        .onChange(of: scenePhase) { _, newPhase in
            switch newPhase {
            case .active:
                Task {
                    await container.notificationScheduler.clearBadge()
                    await container.locationTrackerViewModel.pauseInterruptedRecordingIfNeeded()
                }
            case .background:
                container.locationTrackerViewModel.prepareForSuspension()
            case .inactive:
                break
            @unknown default:
                break
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willTerminateNotification)) { _ in
            container.locationTrackerViewModel.finishForAppTermination()
        }
    }

    private func rootTabBarVisibility(path: NavigationPath, requested: Visibility) -> Visibility {
        // 只有三个一级根页面允许显示 TabBar；进入任意 push 路径后都隐藏，避免子页面被根页面滚动状态改回可见。
        path.isEmpty ? requested : .hidden
    }

    private func handleDeepLink(_ url: URL) {
        guard let deepLink = AppDeepLink(url: url) else {
            return
        }

        switch deepLink {
        case .tool(let route):
            selectedTab = .home
            homePath = NavigationPath()
            homePath.append(route)
        }
    }
}

#Preview {
    AppRoot()
        .environment(AppContainer.preview)
}
