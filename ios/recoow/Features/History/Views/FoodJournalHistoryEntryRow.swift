import SwiftUI

struct FoodJournalHistoryEntryRow: View {
    @Environment(\.locale) private var locale

    let group: FoodDayGroup

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AppIconTileView(
                systemImage: "fork.knife.circle.fill",
                tint: .brown,
                size: AppDesign.historyIconSize,
                foregroundColor: .white,
                backgroundOpacity: 1
            )

            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(titleText)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    HistoryTypeTag(route: .foodJournal)
                }

                Text(AppLocalization.format("更新于 %@", AppFormatters.dateTime(milliseconds: group.updatedAt, locale: locale)))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                if mealKindsText.isEmpty == false {
                    MetadataLineView {
                        MetadataItemView(title: mealKindsText, systemImage: "square.grid.2x2")
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var titleText: String {
        group.title ?? dateText
    }

    private var dateText: String {
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
