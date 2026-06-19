import SwiftUI

struct BillSummarySection: View {
    let hasBills: Bool
    let todayTotalCents: Int64
    let todayIncomeCents: Int64
    let monthTotalCents: Int64
    let monthIncomeCents: Int64
    let monthDiscountCents: Int64

    var body: some View {
        Section("概览") {
            LazyVGrid(columns: columns, spacing: 10) {
                BillSummaryMetricView(
                    title: AppLocalization.string("本月支出"),
                    value: summaryValue(cents: monthTotalCents),
                    systemImage: "arrow.up.right.circle.fill",
                    tint: .red
                )

                BillSummaryMetricView(
                    title: AppLocalization.string("本月收入"),
                    value: summaryValue(cents: monthIncomeCents),
                    systemImage: "arrow.down.left.circle.fill",
                    tint: .green
                )

                BillSummaryMetricView(
                    title: AppLocalization.string("今日支出"),
                    value: summaryValue(cents: todayTotalCents),
                    systemImage: "calendar.badge.minus",
                    tint: .orange
                )

                BillSummaryMetricView(
                    title: AppLocalization.string("今日收入"),
                    value: summaryValue(cents: todayIncomeCents),
                    systemImage: "calendar.badge.plus",
                    tint: .blue
                )
            }

            HStack(spacing: 8) {
                MetadataItemView(titleKey: "本月优惠", systemImage: "tag.fill")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                Spacer(minLength: 8)

                Text(summaryValue(cents: monthDiscountCents))
                    .font(.subheadline)
                    .foregroundStyle(.green)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
            }
            .padding(.top, 2)
        }
    }

    private var columns: [GridItem] {
        [
            GridItem(.flexible(), spacing: 10),
            GridItem(.flexible(), spacing: 10)
        ]
    }

    private func summaryValue(cents: Int64) -> String {
        hasBills ? AppFormatters.money(cents: cents) : "--"
    }
}
