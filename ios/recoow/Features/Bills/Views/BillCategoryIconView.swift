import SwiftUI

struct BillCategoryIconView: View {
    let category: BillCategory
    let size: CGFloat

    var body: some View {
        AppIconTileView(systemImage: category.systemImage, tint: AppPalette.teal, size: size)
    }
}
