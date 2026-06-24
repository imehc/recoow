import SwiftUI

struct FoodMealKindIconView: View {
    let kind: FoodMealKind
    var size: CGFloat

    var body: some View {
        AppIconTileView(
            systemImage: kind.systemImage,
            tint: kind.tint,
            size: size,
            backgroundOpacity: 0.14
        )
    }
}
