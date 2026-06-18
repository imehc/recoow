import SwiftUI

struct BillCategoryIconView: View {
    let category: BillCategory
    let size: CGFloat

    var body: some View {
        Image(systemName: category.systemImage)
            .font(.system(size: size * 0.42, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(.teal.gradient, in: RoundedRectangle(cornerRadius: 8))
    }
}
