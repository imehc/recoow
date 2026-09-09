import SwiftUI

struct StatisticsCheckInProgressSection: View {
    let progresses: [StatisticsCheckInProgress]

    var body: some View {
        Section {
            ForEach(progresses) { progress in
                StatisticsCheckInProgressRow(progress: progress)
            }
        } header: {
            Text("打卡进度")
                .appSectionHeaderStyle()
        }
    }
}
