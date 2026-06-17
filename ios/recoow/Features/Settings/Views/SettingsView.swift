import SwiftUI

struct SettingsView: View {
    @Environment(AppContainer.self) private var container

    var body: some View {
        Form {
            Section("主页功能入口") {
                ForEach(ToolRoute.allCases) { route in
                    FeatureVisibilityToggleRow(
                        route: route,
                        settings: container.featureVisibilitySettings
                    )
                }
            }
        }
        .navigationTitle("设置")
    }
}
