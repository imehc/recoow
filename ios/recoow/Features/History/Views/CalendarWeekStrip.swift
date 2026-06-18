import SwiftUI

struct CalendarWeekStrip: View {
    @Binding var selectedDate: Date
    @Binding var weekAnchorDate: Date
    @State private var isShowingDateSelection = false

    let entryCountsByDay: [Date: Int]

    private let calendar = Calendar.current

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Button(AppLocalization.string("上一周"), systemImage: "chevron.left", action: showPreviousWeek)
                    .labelStyle(.iconOnly)

                Button(action: showDateSelection) {
                    Label(weekRangeTitle, systemImage: "calendar.badge.clock")
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .lineLimit(1)
                }
                    .frame(maxWidth: .infinity)

                Button(AppLocalization.string("下一周"), systemImage: "chevron.right", action: showNextWeek)
                    .labelStyle(.iconOnly)

                Button(AppLocalization.string("今天"), systemImage: "calendar", action: showToday)
                    .font(.subheadline)
                    .disabled(calendar.isDateInToday(selectedDate))
            }
            .buttonStyle(.borderless)

            HStack(spacing: 6) {
                ForEach(weekDates, id: \.self) { date in
                    CalendarWeekDayButton(
                        date: date,
                        count: entryCountsByDay[calendar.startOfDay(for: date), default: 0],
                        isSelected: calendar.isDate(date, inSameDayAs: selectedDate),
                        isToday: calendar.isDateInToday(date),
                    ) {
                        select(date)
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .sheet(isPresented: $isShowingDateSelection) {
            CalendarDateSelectionSheet(
                selectedDate: selectedDate,
                selectDate: selectSpecificDate
            )
            .presentationDetents([.height(CalendarDateSelectionSheet.preferredPresentationHeight)])
            .presentationDragIndicator(.visible)
        }
    }

    private var weekDates: [Date] {
        guard let weekStart = calendar.dateInterval(of: .weekOfYear, for: weekAnchorDate)?.start else {
            return [calendar.startOfDay(for: weekAnchorDate)]
        }

        return (0..<7).compactMap { offset in
            calendar.date(byAdding: .day, value: offset, to: weekStart)
        }
    }

    private var weekRangeTitle: String {
        guard let firstDate = weekDates.first, let lastDate = weekDates.last else {
            return ""
        }

        let first = firstDate.formatted(
            Date.FormatStyle()
                .month(.abbreviated)
                .day(.defaultDigits)
                .locale(AppLocalization.currentLocale)
        )
        let last = lastDate.formatted(
            Date.FormatStyle()
                .month(.abbreviated)
                .day(.defaultDigits)
                .locale(AppLocalization.currentLocale)
        )

        return "\(first) - \(last)"
    }

    private func select(_ date: Date) {
        selectedDate = date
        weekAnchorDate = date
    }

    private func showPreviousWeek() {
        moveWeek(by: -1)
    }

    private func showNextWeek() {
        moveWeek(by: 1)
    }

    private func showToday() {
        let today = Date()
        selectedDate = today
        weekAnchorDate = today
    }

    private func showDateSelection() {
        isShowingDateSelection = true
    }

    private func selectSpecificDate(_ date: Date) {
        selectedDate = date
        weekAnchorDate = date
    }

    private func moveWeek(by value: Int) {
        guard let nextWeek = calendar.date(byAdding: .weekOfYear, value: value, to: weekAnchorDate) else {
            return
        }

        selectedDate = nextWeek
        weekAnchorDate = nextWeek
    }
}
