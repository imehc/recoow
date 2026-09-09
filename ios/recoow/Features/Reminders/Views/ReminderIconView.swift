import SwiftUI

struct ReminderIconView: View {
    let memoryIcon: String
    var size: CGFloat = AppDesign.touchIconSize

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: AppDesign.iconCornerRadius)
                .fill(memoryIcon.contains(".") ? AppPalette.lavender : AppPalette.background)

            if memoryIcon.contains(".") {
                Image(systemName: memoryIcon)
                    .font(.system(size: size * 0.46, weight: .medium))
                    .foregroundStyle(.white)
            } else {
                Text(memoryIcon.isEmpty ? "🔔" : memoryIcon)
                    .font(.system(size: size * 0.48))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
