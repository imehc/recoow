import SwiftUI

enum HomeStyle {
    static let contentMaxWidth = AppDesign.contentMaxWidth
    static let pagePadding = AppDesign.pagePadding
    static let sectionSpacing = AppDesign.sectionSpacing
    static let cardSpacing: CGFloat = 10
    static let cardCornerRadius = AppDesign.cornerRadius
    static let iconCornerRadius = AppDesign.iconCornerRadius
    static let iconSize = AppDesign.rowIconSize
    static let rowPadding: CGFloat = 14
    static let rowSpacing: CGFloat = 12
    static let rowContentInset: CGFloat = rowPadding + iconSize + rowSpacing

    static func tint(for route: ToolRoute) -> Color {
        route.tint
    }

    static func iconForeground(for route: ToolRoute) -> Color {
        AppPalette.iconForeground(for: route.tint)
    }
}

extension View {
    func homeCard() -> some View {
        modifier(HomeCardModifier())
    }
}

private struct HomeCardModifier: ViewModifier {
    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: HomeStyle.cardCornerRadius, style: .continuous)

        content
            .background(AppPalette.surface, in: shape)
            .clipShape(shape)
            .contentShape(shape)
    }
}

struct HomeCardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? AppPalette.ink.opacity(0.05) : .clear)
    }
}

struct HomeStatusLabel: View {
    let title: Text
    let systemImage: String?
    let tint: Color

    var body: some View {
        HStack(spacing: 5) {
            if let systemImage {
                Image(systemName: systemImage)
                    .imageScale(.small)
                    .accessibilityHidden(true)
            }

            title
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(AppDesign.rowSubtitleFont)
        .foregroundStyle(tint)
        .accessibilityElement(children: .combine)
    }
}
