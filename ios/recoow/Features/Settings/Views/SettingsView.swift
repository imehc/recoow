import SwiftUI

struct SettingsView: View {
    @Environment(AppContainer.self) private var container

    var body: some View {
        Form {
            AppPreferencesSection(preferences: container.appPreferences)

            Section {
                ForEach(ToolRegistry.modules) { module in
                    FeatureVisibilityToggleRow(
                        route: module.route,
                        settings: container.featureVisibilitySettings
                    )
                }
            } header: {
                Text("主页功能入口")
            }
        }
        .navigationTitle("设置")
    }
}
