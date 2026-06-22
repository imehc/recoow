import SwiftUI

struct BillSummaryMetricView: View {
    let title: String
    let value: String
    let systemImage: String
    let tint: Color

    var body: some View {
        CompactSummaryMetricView(
            title: title,
            value: value,
            systemImage: systemImage,
            tint: tint
        )
    }
}
