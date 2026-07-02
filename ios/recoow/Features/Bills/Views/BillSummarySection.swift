import SwiftUI

struct BillSummarySection: View {
    let hasBills: Bool
    let todayTotalCents: Int64
    let todayIncomeCents: Int64
    let todayDiscountCents: Int64
    let monthTotalCents: Int64
    let monthIncomeCents: Int64
    let monthDiscountCents: Int64
    let todayStoredValueRechargeCents: Int64
    let todayStoredValueUseCents: Int64
    let monthStoredValueUseCents: Int64

    var body: some View {
        Section("概览") {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
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

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        BillSummaryPillView(
                            title: AppLocalization.string("今日优惠"),
                            value: summaryValue(cents: todayDiscountCents),
                            systemImage: "tag.fill",
                            tint: .green
                        )

                        BillSummaryPillView(
                            title: AppLocalization.string("今日储值充值"),
                            value: summaryValue(cents: todayStoredValueRechargeCents),
                            systemImage: "wallet.pass.fill",
                            tint: .purple
                        )

                        BillSummaryPillView(
                            title: AppLocalization.string("今日储值消费"),
                            value: summaryValue(cents: todayStoredValueUseCents),
                            systemImage: "tram.fill",
                            tint: .orange
                        )

                        BillSummaryPillView(
                            title: AppLocalization.string("本月支出"),
                            value: summaryValue(cents: monthTotalCents),
                            systemImage: "arrow.up.right.circle.fill",
                            tint: .red
                        )

                        BillSummaryPillView(
                            title: AppLocalization.string("本月收入"),
                            value: summaryValue(cents: monthIncomeCents),
                            systemImage: "arrow.down.left.circle.fill",
                            tint: .green
                        )

                        BillSummaryPillView(
                            title: AppLocalization.string("本月优惠"),
                            value: summaryValue(cents: monthDiscountCents),
                            systemImage: "tag.fill",
                            tint: .green
                        )

                        BillSummaryPillView(
                            title: AppLocalization.string("本月储值消费"),
                            value: summaryValue(cents: monthStoredValueUseCents),
                            systemImage: "wallet.pass.fill",
                            tint: .purple
                        )
                    }
                    .padding(.vertical, 1)
                }
                .scrollIndicators(.hidden)
                .accessibilityElement(children: .contain)
            }
            .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
        }
    }

    private func summaryValue(cents: Int64) -> String {
        hasBills ? AppFormatters.money(cents: cents) : "--"
    }
}

private struct BillSummaryPillView: View {
    let title: String
    let value: String
    let systemImage: String
    let tint: Color

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: systemImage)
                .font(.caption.weight(.semibold))
                .foregroundStyle(tint)
                .frame(width: 16)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                Text(value)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }
        }
        .frame(minWidth: 98, minHeight: 38, alignment: .leading)
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .background(tint.opacity(0.08), in: .rect(cornerRadius: AppDesign.cornerRadius))
        .overlay {
            RoundedRectangle(cornerRadius: AppDesign.cornerRadius)
                .stroke(tint.opacity(0.13), lineWidth: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title)，\(value)")
    }
}
