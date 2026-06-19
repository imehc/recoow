import SwiftUI

struct AnniversaryRow: View {
    @Environment(\.locale) private var locale

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
                    MetadataItemView(titleKey: anniversary.dateCalendar.titleKey, systemImage: "calendar")

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
            return AppLocalization.string("已过")
        }

        if days == 0 {
            return AppLocalization.string("今天")
        }

        return AppLocalization.format("%d 天", days)
    }

    private var dateText: String {
        if let nextDate = anniversary.nextOccurrenceDate(from: referenceDate) {
            return AppLocalization.format(
                "下次 %@",
                anniversary.formattedDate(nextDate, locale: locale)
            )
        }

        return AppLocalization.format(
            "发生于 %@",
            anniversary.formattedDate(anniversary.occurredDate, locale: locale)
        )
    }

    private var yearlyText: String {
        let years = anniversary.occurrenceYears(on: anniversary.nextOccurrenceDate(from: referenceDate) ?? referenceDate)
        return years > 0 ? AppLocalization.format("第 %d 年", years) : AppLocalization.string("每年")
    }
}
