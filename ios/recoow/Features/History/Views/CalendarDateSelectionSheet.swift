import SwiftUI

struct CalendarDateSelectionSheet: View {
    static let preferredPresentationHeight: CGFloat = 430

    @Environment(\.dismiss) private var dismiss
    @State private var draftDate: Date

    let selectDate: (Date) -> Void

    init(selectedDate: Date, selectDate: @escaping (Date) -> Void) {
        self.selectDate = selectDate
        _draftDate = State(initialValue: selectedDate)
    }

    var body: some View {
        NavigationStack {
            VStack {
                DatePicker("日期", selection: $draftDate, displayedComponents: .date)
                    .datePickerStyle(.graphical)
            }
            .padding(.horizontal)
            .padding(.bottom)
            .navigationTitle("选择日期")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", action: dismiss.callAsFunction)
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button("确定", action: confirmDate)
                }
            }
        }
    }

    private func confirmDate() {
        selectDate(draftDate)
        dismiss()
    }
}
