import SwiftUI

struct ReminderIconView: View {
    let memoryIcon: String
    var size: CGFloat = 44

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                .fill(.purple.opacity(0.14))

            if memoryIcon.contains(".") {
                Image(systemName: memoryIcon)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.purple)
            } else {
                Text(memoryIcon.isEmpty ? "🔔" : memoryIcon)
                    .font(.title2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
