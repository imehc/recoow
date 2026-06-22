import SwiftUI

struct AnniversaryIconView: View {
    let category: AnniversaryCategory
    var size: CGFloat = AppDesign.listIconSize

    var body: some View {
        AppIconTileView(
            systemImage: category.systemImage,
            tint: category.tint,
            size: size
        )
    }
}
