import SwiftUI

struct ReminderStatusTag: View {
    let reminder: ReminderRecord

    var body: some View {
        MetadataItemView(title: title, systemImage: systemImage)
            .font(.footnote)
            .foregroundStyle(tint)
    }

    private var title: String {
        AppLocalization.string(status.title)
    }

    private var systemImage: String {
        status.systemImage
    }

    private var tint: Color {
        switch status {
        case .completed, .checkedInToday:
            return .green
        case .ready:
            return .purple
        case .broken:
            return .red
        case .disabled:
            return .secondary
        case .upcoming:
            return .blue
        case .ended:
            return .orange
        }
    }

    private var status: ReminderCheckInStatus {
        reminder.checkInStatus()
    }
}
