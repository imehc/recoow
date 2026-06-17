import SwiftUI

/// 应用根导航。后续新增工具时，在 ToolRoute 中追加入口即可继承主页、设置和详情隐藏 Tab 的规则。
struct AppRoot: View {
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

            Tab("设置", systemImage: "gearshape", value: .settings) {
                NavigationStack {
                    SettingsView()
                }
            }
        }
    }
}

#Preview {
    AppRoot()
        .environment(AppContainer.preview)
}
