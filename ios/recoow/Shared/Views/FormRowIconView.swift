import SwiftUI

/// 统一的 SF Symbol 图标底板；列表、历史和表单图标通过 size 区分场景，但共享圆角和图标比例。
struct AppIconTileView: View {
    let systemImage: String
    let tint: Color
    var size: CGFloat = AppDesign.listIconSize
    var foregroundColor: Color?
    var backgroundOpacity = 0.14

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(foregroundColor ?? tint)
            .frame(width: size, height: size)
            .background(tint.opacity(backgroundOpacity), in: .rect(cornerRadius: AppDesign.iconCornerRadius))
            .accessibilityHidden(true)
    }
}

struct FormRowIconView: View {
    let systemImage: String
    var tint: Color

    var body: some View {
        AppIconTileView(
            systemImage: systemImage,
            tint: tint,
            size: AppDesign.formIconSize,
            backgroundOpacity: 0.12
        )
    }
}

struct FormRowIconButton: View {
    let systemImage: String
    var tint: Color
    let accessibilityLabel: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.body.weight(.semibold))
                .frame(width: AppDesign.touchIconSize, height: AppDesign.touchIconSize)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .foregroundStyle(tint)
        .accessibilityLabel(accessibilityLabel)
    }
}

struct FormInfoLabelView: View {
    static let contentLeadingInset: CGFloat = 30

    let title: String
    let systemImage: String
    var tint: Color

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(tint)
                .frame(width: 20, height: 20)
                .accessibilityHidden(true)

            Text(AppLocalization.string(title))
                .foregroundStyle(Color(.label))
        }
    }
}
