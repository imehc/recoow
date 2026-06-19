import SwiftUI

struct MetadataItemView: View {
    private let title: Text
    let systemImage: String

    init(title: Text, systemImage: String) {
        self.title = title
        self.systemImage = systemImage
    }

    init(title: String, systemImage: String) {
        self.title = Text(verbatim: title)
        self.systemImage = systemImage
    }

    init(titleKey: LocalizedStringKey, systemImage: String) {
        self.title = Text(titleKey)
        self.systemImage = systemImage
    }

    var body: some View {
        HStack(spacing: AppDesign.metadataItemSpacing) {
            Image(systemName: systemImage)
                .imageScale(.small)
                .frame(width: 12)

            title
        }
        .lineLimit(1)
    }
}
