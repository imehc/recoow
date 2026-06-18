import SwiftUI

struct BillSummarySection: View {
    let hasBills: Bool
    let todayTotalCents: Int64
    let monthTotalCents: Int64
    let monthDiscountCents: Int64

    var body: some View {
        Section("概览") {
            LabeledContent("今日支出", value: summaryValue(cents: todayTotalCents))
            LabeledContent("本月支出", value: summaryValue(cents: monthTotalCents))
            LabeledContent("本月优惠", value: summaryValue(cents: monthDiscountCents))
        }
    }

    private func summaryValue(cents: Int64) -> String {
        hasBills ? AppFormatters.money(cents: cents) : "--"
    }
}
