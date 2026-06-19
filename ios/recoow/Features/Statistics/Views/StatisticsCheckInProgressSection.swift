import SwiftUI

struct StatisticsCheckInProgressSection: View {
    let progresses: [StatisticsCheckInProgress]

    var body: some View {
        Section("连续打卡进度") {
            ForEach(progresses) { progress in
                StatisticsCheckInProgressRow(progress: progress)
            }
        }
    }
}
