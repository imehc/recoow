import SwiftUI

struct FoodDayGroupRow: View {
    @Environment(\.locale) private var locale

    let group: FoodDayGroup
    var coverPhoto: MediaAttachment?
    var billCount = 0

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            if let coverPhoto {
                PhotoThumbnailView(
                    imageData: coverPhoto.data,
                    systemImage: "fork.knife.circle.fill",
                    size: AppDesign.listIconSize
                )
            } else {
                AppIconTileView(
                    systemImage: "fork.knife.circle.fill",
                    tint: .brown,
                    size: AppDesign.listIconSize,
                    foregroundColor: .white,
                    backgroundOpacity: 1
                )
            }

            VStack(alignment: .leading, spacing: 7) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(titleText)
                        .font(.headline)
                        .lineLimit(1)

                    Spacer(minLength: 8)

                    Text(AppLocalization.format("%d 条", group.entryCount))
                        .font(.headline)
                        .foregroundStyle(.brown)
                }

                if group.title != nil {
                    Text(dateTitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                if mealKindsText.isEmpty == false {
                    Text(mealKindsText)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }

                if billCount > 0 {
                    MetadataLineView {
                        MetadataItemView(title: AppLocalization.format("%d 个账单", billCount), systemImage: "receipt")
                    }
                    .font(.caption)
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var titleText: String {
        group.title ?? dateTitle
    }

    private var dateTitle: String {
        group.date.formatted(
            Date.FormatStyle(date: .abbreviated, time: .omitted)
                .locale(locale)
        )
    }

    private var mealKindsText: String {
        group.mealKinds
            .map(\.localizedTitle)
            .joined(separator: AppLocalization.string("列表分隔符"))
    }
}
