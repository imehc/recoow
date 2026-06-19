import SwiftUI

struct AnniversaryRow: View {
    let anniversary: AnniversaryRecord
    var referenceDate = Date()

    var body: some View {
        HStack(spacing: 12) {
            AnniversaryIconView(category: anniversary.category, size: 56)

            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(anniversary.title)
                        .font(.headline)
                        .lineLimit(2)

                    Spacer(minLength: 8)

                    Text(countdownText)
                        .font(.headline)
                        .foregroundStyle(anniversary.category.tint)
                }

                Text(dateText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                MetadataLineView {
                    MetadataItemView(titleKey: anniversary.category.titleKey, systemImage: anniversary.category.systemImage)

                    if anniversary.isYearly {
                        MetadataItemView(title: yearlyText, systemImage: "repeat")
                    }

                    if anniversary.leadTime != .none {
                        MetadataItemView(titleKey: anniversary.leadTime.titleKey, systemImage: "clock.badge")
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

    private var countdownText: String {
        guard let days = anniversary.daysUntilNext(from: referenceDate) else {
            return "已过"
        }

        if days == 0 {
            return "今天"
        }

        return "\(days) 天"
    }

    private var dateText: String {
        if let nextDate = anniversary.nextOccurrenceDate(from: referenceDate) {
            return "下次 \(AppFormatters.date(milliseconds: AnniversariesViewModel.milliseconds(for: nextDate)))"
        }

        return "发生于 \(AppFormatters.date(milliseconds: anniversary.occurredAt))"
    }

    private var yearlyText: String {
        let years = anniversary.occurrenceYears(on: anniversary.nextOccurrenceDate(from: referenceDate) ?? referenceDate)
        return years > 0 ? "第 \(years) 年" : "每年"
    }
}
