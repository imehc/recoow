import SwiftUI

struct FeatureIconView: View {
    let route: ToolRoute

    var body: some View {
        Image(systemName: route.systemImage)
            .font(.title3)
            .foregroundStyle(route.tint)
            .frame(width: AppDesign.rowIconSize, height: AppDesign.rowIconSize)
            .background(route.tint.opacity(0.12), in: .rect(cornerRadius: AppDesign.cornerRadius))
            .accessibilityHidden(true)
    }
}
