import SwiftUI

struct DiaryCalendarFilterView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: DiaryViewModel
    @State private var selectedDate: Date

    init(viewModel: DiaryViewModel) {
        self.viewModel = viewModel
        _selectedDate = State(initialValue: viewModel.selectedDate ?? Date())
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(AppLocalization.string("日期")) {
                    DatePicker(
                        AppLocalization.string("选择日期"),
                        selection: $selectedDate,
                        displayedComponents: [.date]
                    )
                    .datePickerStyle(.graphical)
                }

                Section(AppLocalization.string("当天日记")) {
                    let entries = entries(on: selectedDate)
                    if entries.isEmpty {
                        Text(AppLocalization.string("当天没有日记"))
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(entries) { entry in
                            LabeledContent(entry.title, value: AppFormatters.dateTime(milliseconds: entry.occurredAt))
                        }
                    }
                }
            }
            .navigationTitle(AppLocalization.string("日历"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(AppLocalization.string("清除")) {
                        viewModel.selectedDate = nil
                        dismiss()
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button(AppLocalization.string("完成")) {
                        viewModel.selectedDate = selectedDate
                        dismiss()
                    }
                }
            }
        }
    }

    private func entries(on date: Date) -> [DiaryEntry] {
        viewModel.entries.filter {
            Calendar.current.isDate($0.occurredDate, inSameDayAs: date)
        }
    }
}
