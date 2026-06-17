import SwiftUI

struct FeatureVisibilityToggleRow: View {
    let route: ToolRoute
    let settings: FeatureVisibilitySettings

    @State private var isVisible: Bool

    init(route: ToolRoute, settings: FeatureVisibilitySettings) {
        self.route = route
        self.settings = settings
        _isVisible = State(initialValue: settings.isVisible(route))
    }

    var body: some View {
        Toggle(isOn: $isVisible) {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(route.titleKey)

                    Text(route.subtitleKey)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: route.systemImage)
                    .foregroundStyle(route.tint)
            }
        }
        .onChange(of: isVisible) { _, newValue in
            settings.setVisible(newValue, for: route)
        }
        .onChange(of: settings.isVisible(route)) { _, newValue in
            isVisible = newValue
        }
    }
}
