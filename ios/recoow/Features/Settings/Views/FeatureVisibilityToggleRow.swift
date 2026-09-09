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
            HStack(spacing: 12) {
                AppIconTileView(systemImage: route.systemImage, tint: route.tint, size: AppDesign.rowIconSize)

                VStack(alignment: .leading, spacing: 4) {
                    Text(route.titleKey)
                        .font(AppDesign.rowTitleFont)
                        .foregroundStyle(AppPalette.ink)

                    Text(route.subtitleKey)
                        .font(.footnote)
                        .foregroundStyle(AppPalette.secondaryInk)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, 4)
        }
        .onChange(of: isVisible) { _, newValue in
            settings.setVisible(newValue, for: route)
        }
        .onChange(of: settings.isVisible(route)) { _, newValue in
            isVisible = newValue
        }
    }
}
