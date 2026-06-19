import Combine
import SwiftUI

/// 应用根导航。后续新增工具时，在 ToolRoute 中追加入口即可继承主页、设置和详情隐藏 Tab 的规则。
struct AppRoot: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedTab: AppTab = .home

    var body: some View {
        TabView(selection: $selectedTab) {
            Tab("首页", systemImage: "house", value: .home) {
                NavigationStack {
                    HomeView {
                        selectedTab = .settings
                    }
                }
            }

            Tab("历史", systemImage: "clock", value: .history) {
                NavigationStack {
                    TrackHistoryView()
                }
            }

            Tab("统计", systemImage: "chart.bar.xaxis", value: .statistics) {
                NavigationStack {
                    StatisticsView(
                        openHistory: {
                            container.historyFilterRequest = nil
                            selectedTab = .history
                        }
                    )
                }
            }

            Tab("设置", systemImage: "gearshape", value: .settings) {
                NavigationStack {
                    SettingsView()
                }
            }
        }
        .environment(\.locale, container.appPreferences.locale)
        .preferredColorScheme(container.appPreferences.colorScheme)
        .task {
            await container.notificationScheduler.clearBadge()
            await container.locationTrackerViewModel.finishInterruptedRecordingIfNeeded()
        }
        .onChange(of: scenePhase) { _, newPhase in
            switch newPhase {
            case .active:
                Task {
                    await container.notificationScheduler.clearBadge()
                    await container.locationTrackerViewModel.finishInterruptedRecordingIfNeeded()
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
}

#Preview {
    AppRoot()
        .environment(AppContainer.preview)
}
