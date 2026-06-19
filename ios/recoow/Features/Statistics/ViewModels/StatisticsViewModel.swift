import Foundation
import Observation

@MainActor
@Observable
final class StatisticsViewModel {
    var tracks: [Track] = []
    var decisionRecords: [DecisionChoiceRecord] = []
    var items: [StoredItem] = []
    var reminders: [ReminderRecord] = []
    var bills: [BillRecord] = []
    var anniversaries: [AnniversaryRecord] = []

    @ObservationIgnored private let trackRepository: TrackRepository
    @ObservationIgnored private let decisionRepository: DecisionRepository
    @ObservationIgnored private let itemLocatorRepository: ItemLocatorRepository
    @ObservationIgnored private let reminderRepository: ReminderRepository
    @ObservationIgnored private let billRepository: BillRepository
    @ObservationIgnored private let anniversaryRepository: AnniversaryRepository
    @ObservationIgnored private var observationTasks: [Task<Void, Never>] = []

    private var errorMessagesBySource: [String: String] = [:]
    private let calendar = Calendar.current

    init(
        trackRepository: TrackRepository,
        decisionRepository: DecisionRepository,
        itemLocatorRepository: ItemLocatorRepository,
        reminderRepository: ReminderRepository,
        billRepository: BillRepository,
        anniversaryRepository: AnniversaryRepository
    ) {
        self.trackRepository = trackRepository
        self.decisionRepository = decisionRepository
        self.itemLocatorRepository = itemLocatorRepository
        self.reminderRepository = reminderRepository
        self.billRepository = billRepository
        self.anniversaryRepository = anniversaryRepository
    }

    deinit {
        observationTasks.forEach { $0.cancel() }
    }

    var totalRecordCount: Int {
        tracks.count + decisionRecords.count + items.count + reminders.count + bills.count + anniversaries.count
    }

    var todayRecordCount: Int {
        allEntryDates.filter(calendar.isDateInToday).count
    }

    var activeDayCount: Int {
        Set(allEntryDates.map { calendar.startOfDay(for: $0) }).count
    }

    var latestRecordDate: Date? {
        allEntryDates.max()
    }

    var errorMessages: [String] {
        errorMessagesBySource.values.sorted()
    }

    var featureSummaries: [StatisticsFeatureSummary] {
        let snapshot = ToolStatisticsSnapshot(
            tracks: tracks,
            decisionRecords: decisionRecords,
            items: items,
            reminders: reminders,
            bills: bills,
            anniversaries: anniversaries
        )

        return ToolRegistry.modules.map { module in
            makeSummary(route: module.route, dates: module.statisticsDates(in: snapshot))
        }
    }

    func recentUsagePoints(locale: Locale) -> [StatisticsUsageChartPoint] {
        let today = calendar.startOfDay(for: Date())
        return (0..<7).reversed().compactMap { offset in
            guard let day = calendar.date(byAdding: .day, value: -offset, to: today) else { return nil }
            let count = allEntryDates.filter { calendar.isDate($0, inSameDayAs: day) }.count
            return StatisticsUsageChartPoint(
                id: day.ISO8601Format(),
                label: format(day, template: "EEE", locale: locale),
                count: count
            )
        }
    }

    var continuousCheckInProgresses: [StatisticsCheckInProgress] {
        reminders.compactMap { reminder in
            guard reminder.scheduleKind == .continuousDays,
                  let totalDays = reminder.progressTotalDays,
                  totalDays > 1,
                  let progressFraction = reminder.progressFraction else {
                return nil
            }

            return StatisticsCheckInProgress(
                id: reminder.id,
                title: reminder.title,
                completedDays: reminder.progressCompletedDays,
                totalDays: totalDays,
                progressFraction: progressFraction,
                isCompleted: reminder.isCompleted
            )
        }
        .sorted { lhs, rhs in
            if lhs.isCompleted != rhs.isCompleted {
                return lhs.isCompleted == false
            }

            if lhs.progressFraction == rhs.progressFraction {
                return lhs.title < rhs.title
            }

            return lhs.progressFraction > rhs.progressFraction
        }
    }

    func startObserving() {
        guard observationTasks.isEmpty else { return }
        observeTracks()
        observeDecisionRecords()
        observeItems()
        observeReminders()
        observeBills()
        observeAnniversaries()
    }

    func billPoints(for period: StatisticsBillPeriod, locale: Locale) -> [StatisticsBillChartPoint] {
        switch period {
        case .week:
            return dailyBillPoints(for: .weekOfYear, labelTemplate: "EEE", locale: locale)
        case .month:
            return dailyBillPoints(for: .month, labelTemplate: "d", locale: locale)
        case .year:
            return monthlyBillPoints(locale: locale)
        }
    }

    func billCategoryPoints(for period: StatisticsBillPeriod) -> [StatisticsBillCategoryPoint] {
        let groupedBills = Dictionary(grouping: expenseBills(in: period), by: \.billCategory)
        return groupedBills.map { category, bills in
            StatisticsBillCategoryPoint(
                category: category,
                totalCents: bills.reduce(0) { $0 + $1.finalAmountCents },
                count: bills.count
            )
        }
        .sorted { lhs, rhs in
            if lhs.totalCents == rhs.totalCents {
                return lhs.category.localizedTitle < rhs.category.localizedTitle
            }

            return lhs.totalCents > rhs.totalCents
        }
    }

    func billIncomeCategoryPoints(for period: StatisticsBillPeriod) -> [StatisticsBillIncomeCategoryPoint] {
        let groupedBills = Dictionary(grouping: incomeBills(in: period), by: \.billIncomeCategory)
        return groupedBills.map { category, bills in
            StatisticsBillIncomeCategoryPoint(
                category: category,
                totalCents: bills.reduce(0) { $0 + $1.finalAmountCents },
                count: bills.count
            )
        }
        .sorted { lhs, rhs in
            if lhs.totalCents == rhs.totalCents {
                return lhs.category.localizedTitle < rhs.category.localizedTitle
            }

            return lhs.totalCents > rhs.totalCents
        }
    }

    func billCount(for period: StatisticsBillPeriod) -> Int {
        bills(in: period).count
    }

    func bills(for period: StatisticsBillPeriod) -> [BillRecord] {
        bills(in: period).sorted { $0.occurredAt > $1.occurredAt }
    }

    func billExpenseTotalCents(for period: StatisticsBillPeriod) -> Int64 {
        expenseBills(in: period).reduce(0) { $0 + $1.finalAmountCents }
    }

    func billIncomeTotalCents(for period: StatisticsBillPeriod) -> Int64 {
        incomeBills(in: period).reduce(0) { $0 + $1.finalAmountCents }
    }

    func billDiscountTotalCents(for period: StatisticsBillPeriod) -> Int64 {
        expenseBills(in: period).reduce(0) { $0 + $1.discountAmountCents }
    }

    func billDateInterval(for period: StatisticsBillPeriod) -> DateInterval? {
        let component: Calendar.Component = switch period {
        case .week:
            .weekOfYear
        case .month:
            .month
        case .year:
            .year
        }

        return calendar.dateInterval(of: component, for: Date())
    }

    private var allEntryDates: [Date] {
        tracks.map { date(milliseconds: $0.startedAt) }
        + decisionRecords.map { date(milliseconds: $0.selectedAt) }
        + items.map { date(milliseconds: $0.updatedAt) }
        + reminders.map { date(milliseconds: $0.scheduledAt) }
        + bills.map(\.occurredDate)
        + anniversaries.map(\.occurredDate)
    }

    private func observeTracks() {
        observationTasks.append(Task { [weak self] in
            guard let self else { return }

            for await result in trackRepository.observeTracks() {
                switch result {
                case .success(let tracks):
                    self.tracks = tracks
                    self.removeError(prefix: "tracks")
                case .failure(let error):
                    self.setError(error.localizedDescription, prefix: "tracks")
                }
            }
        })
    }

    private func observeDecisionRecords() {
        observationTasks.append(Task { [weak self] in
            guard let self else { return }

            for await result in decisionRepository.observeChoiceRecords() {
                switch result {
                case .success(let records):
                    self.decisionRecords = records
                    self.removeError(prefix: "decision")
                case .failure(let error):
                    self.setError(error.localizedDescription, prefix: "decision")
                }
            }
        })
    }

    private func observeItems() {
        observationTasks.append(Task { [weak self] in
            guard let self else { return }

            for await result in itemLocatorRepository.observeItems() {
                switch result {
                case .success(let items):
                    self.items = items
                    self.removeError(prefix: "items")
                case .failure(let error):
                    self.setError(error.localizedDescription, prefix: "items")
                }
            }
        })
    }

    private func observeReminders() {
        observationTasks.append(Task { [weak self] in
            guard let self else { return }

            for await result in reminderRepository.observeReminders() {
                switch result {
                case .success(let reminders):
                    self.reminders = reminders
                    self.removeError(prefix: "reminders")
                case .failure(let error):
                    self.setError(error.localizedDescription, prefix: "reminders")
                }
            }
        })
    }

    private func observeBills() {
        observationTasks.append(Task { [weak self] in
            guard let self else { return }

            for await result in billRepository.observeBills() {
                switch result {
                case .success(let bills):
                    self.bills = bills
                    self.removeError(prefix: "bills")
                case .failure(let error):
                    self.setError(error.localizedDescription, prefix: "bills")
                }
            }
        })
    }

    private func observeAnniversaries() {
        observationTasks.append(Task { [weak self] in
            guard let self else { return }

            for await result in anniversaryRepository.observeAnniversaries() {
                switch result {
                case .success(let anniversaries):
                    self.anniversaries = anniversaries
                    self.removeError(prefix: "anniversaries")
                case .failure(let error):
                    self.setError(error.localizedDescription, prefix: "anniversaries")
                }
            }
        })
    }

    private func makeSummary(route: ToolRoute, dates: [Date]) -> StatisticsFeatureSummary {
        StatisticsFeatureSummary(
            route: route,
            count: dates.count,
            todayCount: dates.filter(calendar.isDateInToday).count,
            latestDate: dates.max()
        )
    }

    private func dailyBillPoints(for component: Calendar.Component, labelTemplate: String, locale: Locale) -> [StatisticsBillChartPoint] {
        guard let interval = calendar.dateInterval(of: component, for: Date()) else { return [] }

        var points: [StatisticsBillChartPoint] = []
        var day = calendar.startOfDay(for: interval.start)

        while day < interval.end {
            let nextDay = calendar.date(byAdding: .day, value: 1, to: day) ?? interval.end
            let dayBills = bills.filter { bill in
                bill.occurredDate >= day && bill.occurredDate < nextDay
            }
            points.append(
                StatisticsBillChartPoint(
                    id: day.ISO8601Format(),
                    label: format(day, template: labelTemplate, locale: locale),
                    expenseCents: dayBills
                        .filter { $0.billType == .expense }
                        .reduce(0) { $0 + $1.finalAmountCents },
                    incomeCents: dayBills
                        .filter { $0.billType == .income }
                        .reduce(0) { $0 + $1.finalAmountCents },
                    count: dayBills.count
                )
            )
            day = nextDay
        }

        return points
    }

    private func monthlyBillPoints(locale: Locale) -> [StatisticsBillChartPoint] {
        guard let interval = calendar.dateInterval(of: .year, for: Date()) else { return [] }

        var points: [StatisticsBillChartPoint] = []
        var month = interval.start

        while month < interval.end {
            let nextMonth = calendar.date(byAdding: .month, value: 1, to: month) ?? interval.end
            let monthBills = bills.filter { bill in
                bill.occurredDate >= month && bill.occurredDate < nextMonth
            }
            points.append(
                StatisticsBillChartPoint(
                    id: month.ISO8601Format(),
                    label: format(month, template: "MMM", locale: locale),
                    expenseCents: monthBills
                        .filter { $0.billType == .expense }
                        .reduce(0) { $0 + $1.finalAmountCents },
                    incomeCents: monthBills
                        .filter { $0.billType == .income }
                        .reduce(0) { $0 + $1.finalAmountCents },
                    count: monthBills.count
                )
            )
            month = nextMonth
        }

        return points
    }

    private func bills(in period: StatisticsBillPeriod) -> [BillRecord] {
        guard let interval = billDateInterval(for: period) else { return [] }
        return bills.filter { interval.contains($0.occurredDate) }
    }

    private func expenseBills(in period: StatisticsBillPeriod) -> [BillRecord] {
        bills(in: period).filter { $0.billType == .expense }
    }

    private func incomeBills(in period: StatisticsBillPeriod) -> [BillRecord] {
        bills(in: period).filter { $0.billType == .income }
    }

    private func date(milliseconds: Int64) -> Date {
        Date(timeIntervalSince1970: Double(milliseconds) / 1000)
    }

    private func format(_ date: Date, template: String, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.setLocalizedDateFormatFromTemplate(template)
        return formatter.string(from: date)
    }

    private func setError(_ message: String, prefix: String) {
        errorMessagesBySource[prefix] = message
    }

    private func removeError(prefix: String) {
        errorMessagesBySource[prefix] = nil
    }
}
