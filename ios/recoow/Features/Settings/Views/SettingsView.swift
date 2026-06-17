import SwiftUI

struct SettingsView: View {
    @Environment(AppContainer.self) private var container

    var body: some View {
        Form {
            AppPreferencesSection(preferences: container.appPreferences)

            Section {
                ForEach(ToolRoute.allCases) { route in
                    FeatureVisibilityToggleRow(
                        route: route,
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
