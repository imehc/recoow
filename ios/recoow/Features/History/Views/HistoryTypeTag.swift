import SwiftUI

struct HistoryTypeTag: View {
    let route: ToolRoute

    var body: some View {
        MetadataItemView(titleKey: route.titleKey, systemImage: route.systemImage)
            .font(.footnote)
            .foregroundStyle(route.tint)
    }
}
