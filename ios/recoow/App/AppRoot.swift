import Combine
import SwiftUI

/// 应用根导航。后续新增工具时，在 ToolRoute 中追加入口即可继承主页、设置和详情隐藏 Tab 的规则。
struct AppRoot: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedTab: AppTab = .home
    @State private var homePath = NavigationPath()

    var body: some View {
        let language = container.appPreferences.language

        TabView(selection: $selectedTab) {
            Tab(AppLocalization.string("首页", language: language), systemImage: "house", value: .home) {
                NavigationStack(path: $homePath) {
                    HomeView {
                        selectedTab = .settings
                    }
                }
            }

            Tab(AppLocalization.string("历史", language: language), systemImage: "clock", value: .history) {
                NavigationStack {
                    TrackHistoryView()
                }
            }

            Tab(AppLocalization.string("统计", language: language), systemImage: "chart.bar.xaxis", value: .statistics) {
                NavigationStack {
                    StatisticsView(
                        openHistory: {
                            container.historyFilterRequest = nil
                            selectedTab = .history
                        }
                    )
                }
            }

            Tab(AppLocalization.string("设置", language: language), systemImage: "gearshape", value: .settings) {
                NavigationStack {
                    SettingsView()
                }
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
