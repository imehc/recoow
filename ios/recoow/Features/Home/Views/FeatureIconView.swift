import SwiftUI

struct FeatureIconView: View {
    let route: ToolRoute
    var size: CGFloat = AppDesign.rowIconSize

    var body: some View {
        Image(systemName: route.systemImage)
            .font(.system(size: size * 0.48, weight: .medium))
            .symbolRenderingMode(.monochrome)
            .foregroundStyle(HomeStyle.iconForeground(for: route))
            .frame(width: size, height: size)
            .background(HomeStyle.tint(for: route), in: .rect(cornerRadius: HomeStyle.iconCornerRadius))
            .accessibilityHidden(true)
    }
}
