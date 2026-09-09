import SwiftUI

struct StatisticsFeatureUsageSection: View {
    let summaries: [StatisticsFeatureSummary]

    var body: some View {
        Section {
            ForEach(summaries) { summary in
                StatisticsFeatureUsageRow(summary: summary)
            }
        } header: {
            Text("各板块")
                .appSectionHeaderStyle()
        }
    }
}
