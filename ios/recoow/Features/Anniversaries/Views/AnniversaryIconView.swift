import SwiftUI

struct AnniversaryIconView: View {
    let category: AnniversaryCategory
    var size: CGFloat = 56

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                .fill(category.tint.opacity(0.14))

            Image(systemName: category.systemImage)
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundStyle(category.tint)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
