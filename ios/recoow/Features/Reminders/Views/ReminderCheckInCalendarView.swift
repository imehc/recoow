import SwiftUI

/// 打卡记录的日历视图：按月份标记已打卡的日期。
///
/// - 普通打卡：主题色实心；补签：主题色浅底 + 描边，点按后在下方显示该天的补签备注。
/// - 未打卡且可补签的日期：主题色虚线，点按进入补签。
///
/// 只呈现已有的 `completionRecords`，不参与打卡、补签、连续天数等业务计算。
struct ReminderCheckInCalendarView: View {
    @Environment(\.locale) private var locale
    @Environment(\.calendar) private var environmentCalendar

    let reminder: ReminderRecord
    let onRequestMakeUp: ((Date) -> Void)?

    @State private var visibleMonth: Date
    @State private var selectedMakeUpDateKey: String?

    init(reminder: ReminderRecord, onRequestMakeUp: ((Date) -> Void)? = nil) {
        self.reminder = reminder
        self.onRequestMakeUp = onRequestMakeUp

        let records = reminder.completionRecords
        let latestCompletionDate = records.last.flatMap { ReminderRecord.date(fromDateKey: $0.dateKey) }
        _visibleMonth = State(
            initialValue: Self.startOfMonth(latestCompletionDate ?? reminder.scheduledDate, calendar: .current)
        )
    }

    var body: some View {
        let makeUpDateKeys = missedDateKeys

        return VStack(spacing: 12) {
            monthHeader
            weekdayHeader
            calendarGrid(makeUpDateKeys: makeUpDateKeys)

            if let selectedMakeUpNote {
                Divider()
                Text(selectedMakeUpNote)
                    .font(AppDesign.metadataFont)
                    .foregroundStyle(AppPalette.secondaryInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.vertical, 4)
    }

    // MARK: - 月份导航

    private var monthHeader: some View {
        HStack(spacing: 8) {
            monthNavigationButton(systemImage: "chevron.left", titleKey: "上一月", isEnabled: canGoToPreviousMonth) {
                shiftMonth(by: -1)
            }

            Text(monthTitle)
                .font(.headline)
                .foregroundStyle(AppPalette.ink)
                .frame(maxWidth: .infinity)

            monthNavigationButton(systemImage: "chevron.right", titleKey: "下一月", isEnabled: canGoToNextMonth) {
                shiftMonth(by: 1)
            }
        }
    }

    private func monthNavigationButton(
        systemImage: String,
        titleKey: String,
        isEnabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.body.weight(.semibold))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .disabled(isEnabled == false)
        .accessibilityLabel(AppLocalization.string(titleKey))
    }

    private var monthTitle: String {
        visibleMonth.formatted(.dateTime.year().month(.wide).locale(locale))
    }

    private var canGoToPreviousMonth: Bool {
        calendar.compare(visibleMonth, to: earliestMonth, toGranularity: .month) == .orderedDescending
    }

    private var canGoToNextMonth: Bool {
        calendar.compare(visibleMonth, to: latestMonth, toGranularity: .month) == .orderedAscending
    }

    private var earliestMonth: Date {
        let earliest = ([reminder.scheduledDate] + completionDates).min() ?? reminder.scheduledDate
        return Self.startOfMonth(earliest, calendar: calendar)
    }

    private var latestMonth: Date {
        var candidates = [reminder.scheduledDate] + completionDates

        if let endDate = reminder.endDate {
            candidates.append(endDate)
        }

        if reminder.isCompleted == false && reminder.isEndedEarly == false {
            candidates.append(Date())
        }

        return Self.startOfMonth(candidates.max() ?? reminder.scheduledDate, calendar: calendar)
    }

    private func shiftMonth(by value: Int) {
        guard let target = calendar.date(byAdding: .month, value: value, to: visibleMonth) else { return }

        visibleMonth = target
        selectedMakeUpDateKey = nil
    }

    // MARK: - 星期标题

    private var weekdayHeader: some View {
        HStack(spacing: 0) {
            ForEach(Array(weekdaySymbols.enumerated()), id: \.offset) { _, symbol in
                Text(symbol)
                    .font(AppDesign.metadataFont)
                    .foregroundStyle(AppPalette.secondaryInk)
                    .frame(maxWidth: .infinity)
            }
        }
        .accessibilityHidden(true)
    }

    private var weekdaySymbols: [String] {
        let symbols = calendar.veryShortWeekdaySymbols
        guard symbols.count == 7 else { return symbols }

        let firstIndex = max(0, min(6, calendar.firstWeekday - 1))
        return (0..<7).map { symbols[(firstIndex + $0) % 7] }
    }

    // MARK: - 日期网格

    private func calendarGrid(makeUpDateKeys: Set<String>) -> some View {
        Grid(horizontalSpacing: 0, verticalSpacing: 2) {
            ForEach(Array(weeks.enumerated()), id: \.offset) { _, week in
                GridRow {
                    ForEach(Array(week.enumerated()), id: \.offset) { _, day in
                        if let day {
                            dayCell(day, makeUpDateKeys: makeUpDateKeys)
                        } else {
                            Color.clear
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                    }
                }
            }
        }
    }

    private var weeks: [[Date?]] {
        let cells = monthCells
        guard cells.isEmpty == false else { return [] }

        return stride(from: 0, to: cells.count, by: 7).map { index in
            Array(cells[index..<min(index + 7, cells.count)])
        }
    }

    private var monthCells: [Date?] {
        guard let monthStart = calendar.dateInterval(of: .month, for: visibleMonth)?.start,
              let dayRange = calendar.range(of: .day, in: .month, for: visibleMonth) else {
            return []
        }

        let firstWeekday = calendar.component(.weekday, from: monthStart)
        let leadingCount = (firstWeekday - calendar.firstWeekday + 7) % 7
        var cells: [Date?] = Array(repeating: nil, count: leadingCount)

        for offset in 0..<dayRange.count {
            cells.append(calendar.date(byAdding: .day, value: offset, to: monthStart))
        }

        let trailingCount = (7 - cells.count % 7) % 7
        cells.append(contentsOf: Array(repeating: nil, count: trailingCount))
        return cells
    }

    private func dayCell(_ date: Date, makeUpDateKeys: Set<String>) -> some View {
        let dateKey = ReminderRecord.dateKey(for: date, calendar: calendar)
        let completion = completionsByKey[dateKey]
        let isMakeUpEligible = completion == nil && makeUpDateKeys.contains(dateKey)
        let isPlainDay = completion == nil && isMakeUpEligible == false

        return Button {
            handleTap(date: date, dateKey: dateKey, completion: completion, isMakeUpEligible: isMakeUpEligible)
        } label: {
            dayVisual(for: date, completionKind: completion?.kind, isMakeUpEligible: isMakeUpEligible)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(recordAccessibilityText(for: date, completion: completion))
        .accessibilityHint(isMakeUpEligible ? AppLocalization.string("点击可补签") : "")
        .accessibilityHidden(isPlainDay)
    }

    /// 只有点按补签日期才展示该天备注；点按打卡、未打卡或可补签日期都会先隐藏已有备注。
    private func handleTap(
        date: Date,
        dateKey: String,
        completion: ReminderCheckInCompletion?,
        isMakeUpEligible: Bool
    ) {
        guard completion?.kind == .makeUp else {
            selectedMakeUpDateKey = nil

            if isMakeUpEligible {
                onRequestMakeUp?(date)
            }
            return
        }

        selectedMakeUpDateKey = dateKey
    }

    private func dayVisual(
        for date: Date,
        completionKind: ReminderCheckInCompletionKind?,
        isMakeUpEligible: Bool
    ) -> some View {
        let isToday = calendar.isDateInToday(date)
        let isFuture = date > Date()

        return Text(String(calendar.component(.day, from: date)))
            .font(AppDesign.summaryValueFont)
            .monospacedDigit()
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .foregroundStyle(numberColor(completionKind: completionKind, isFuture: isFuture))
            .frame(width: 34, height: 30)
            .background {
                if completionKind == .checkIn {
                    Capsule().fill(AppPalette.accent)
                } else if completionKind == .makeUp {
                    Capsule().fill(AppPalette.accent.opacity(0.18))
                }
            }
            .padding(3)
            .overlay {
                if completionKind == .makeUp {
                    Capsule().strokeBorder(AppPalette.accent, lineWidth: 1)
                } else if completionKind == nil && isToday {
                    Capsule().strokeBorder(AppPalette.accent, lineWidth: 1.5)
                } else if completionKind == nil && isMakeUpEligible {
                    Capsule().strokeBorder(
                        AppPalette.accent,
                        style: StrokeStyle(lineWidth: 1.5, dash: [3, 3])
                    )
                }
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
    }

    private func numberColor(completionKind: ReminderCheckInCompletionKind?, isFuture: Bool) -> Color {
        switch completionKind {
        case .checkIn:
            return AppPalette.accentForeground
        case .makeUp:
            return AppPalette.accent
        case nil:
            return isFuture ? AppPalette.secondaryInk : AppPalette.ink
        }
    }

    // MARK: - 数据

    private var calendar: Calendar {
        var calendar = environmentCalendar
        calendar.locale = locale
        return calendar
    }

    private var completionDates: [Date] {
        reminder.completionRecords.compactMap { ReminderRecord.date(fromDateKey: $0.dateKey, calendar: calendar) }
    }

    /// 可补签的日期（任务起始日到昨天）。使用与右上角「补签」相同的判定，保证入口一致。
    private var missedDateKeys: Set<String> {
        Set(reminder.missedCheckInDates().map { ReminderRecord.dateKey(for: $0, calendar: calendar) })
    }

    private var completionsByKey: [String: ReminderCheckInCompletion] {
        Dictionary(reminder.completionRecords.map { ($0.dateKey, $0) }, uniquingKeysWith: { _, latest in latest })
    }

    private func recordAccessibilityText(for date: Date, completion: ReminderCheckInCompletion?) -> String {
        [dateText(for: date), completion.map { AppLocalization.string($0.kind.title) }, completion?.note]
            .compactMap { $0 }
            .joined(separator: AppLocalization.string("列表分隔符"))
    }

    private var selectedMakeUpNote: String? {
        guard let selectedMakeUpDateKey,
              let completion = completionsByKey[selectedMakeUpDateKey],
              completion.kind == .makeUp else {
            return nil
        }

        return completion.note
    }

    private func dateText(for date: Date) -> String {
        AppFormatters.date(milliseconds: RemindersViewModel.milliseconds(for: date), locale: locale)
    }

    private static func startOfMonth(_ date: Date, calendar: Calendar) -> Date {
        calendar.dateInterval(of: .month, for: date)?.start ?? date
    }
}
