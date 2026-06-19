import SwiftUI

struct AnniversaryHistoryEntryRow: View {
    let anniversary: AnniversaryRecord

    var body: some View {
        HStack(spacing: 12) {
            AnniversaryIconView(category: anniversary.category, size: 64)

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(anniversary.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    HistoryTypeTag(route: .anniversaries)
                }

                Text(AppFormatters.date(milliseconds: anniversary.occurredAt))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                MetadataLineView {
                    MetadataItemView(titleKey: anniversary.category.titleKey, systemImage: anniversary.category.systemImage)

                    if anniversary.isYearly {
                        MetadataItemView(title: "每年", systemImage: "repeat")
                    }

                    if let days = anniversary.daysUntilNext() {
                        MetadataItemView(title: days == 0 ? "今天" : "\(days) 天", systemImage: "calendar")
                    } else {
                        MetadataItemView(title: "已过去", systemImage: "clock.badge.exclamationmark")
                    }
                }

                if let note = anniversary.note {
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 4)
    }
}
