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
                DatePicker(AppLocalization.string("日期"), selection: $draftDate, displayedComponents: .date)
                    .datePickerStyle(.graphical)
            }
            .padding(.horizontal)
            .padding(.bottom)
            .navigationTitle(AppLocalization.string("选择日期"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalization.string("取消"), action: dismiss.callAsFunction)
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button(AppLocalization.string("确定"), action: confirmDate)
                }
            }
        }
        .environment(\.locale, AppLocalization.currentLocale)
    }

    private func confirmDate() {
        selectDate(draftDate)
        dismiss()
    }
}
