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

    @ObservationIgnored private let trackRepository: TrackRepository
    @ObservationIgnored private let decisionRepository: DecisionRepository
    @ObservationIgnored private let itemLocatorRepository: ItemLocatorRepository
    @ObservationIgnored private let reminderRepository: ReminderRepository
    @ObservationIgnored private let billRepository: BillRepository
    @ObservationIgnored private var observationTasks: [Task<Void, Never>] = []

    private var errorMessagesBySource: [String: String] = [:]
    private let calendar = Calendar.current

    init(
        trackRepository: TrackRepository,
        decisionRepository: DecisionRepository,
        itemLocatorRepository: ItemLocatorRepository,
        reminderRepository: ReminderRepository,
        billRepository: BillRepository
    ) {
        self.trackRepository = trackRepository
        self.decisionRepository = decisionRepository
        self.itemLocatorRepository = itemLocatorRepository
        self.reminderRepository = reminderRepository
        self.billRepository = billRepository
    }

    deinit {
        observationTasks.forEach { $0.cancel() }
    }

    var totalRecordCount: Int {
        tracks.count + decisionRecords.count + items.count + reminders.count + bills.count
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
        [
            makeSummary(route: .locationTracker, dates: tracks.map { date(milliseconds: $0.startedAt) }),
            makeSummary(route: .decisionMaker, dates: decisionRecords.map { date(milliseconds: $0.selectedAt) }),
            makeSummary(route: .itemLocator, dates: items.map { date(milliseconds: $0.updatedAt) }),
            makeSummary(route: .reminders, dates: reminders.map { date(milliseconds: $0.scheduledAt) }),
            makeSummary(route: .bills, dates: bills.map(\.occurredDate))
        ]
    }

    var recentUsagePoints: [StatisticsUsageChartPoint] {
        let today = calendar.startOfDay(for: Date())
        return (0..<7).reversed().compactMap { offset in
            guard let day = calendar.date(byAdding: .day, value: -offset, to: today) else { return nil }
            let count = allEntryDates.filter { calendar.isDate($0, inSameDayAs: day) }.count
            return StatisticsUsageChartPoint(
                id: day.ISO8601Format(),
                label: format(day, template: "EEE"),
                count: count
            )
        }
    }

    func startObserving() {
        guard observationTasks.isEmpty else { return }
        observeTracks()
        observeDecisionRecords()
        observeItems()
        observeReminders()
        observeBills()
    }

    func billPoints(for period: StatisticsBillPeriod) -> [StatisticsBillChartPoint] {
        switch period {
        case .week:
            return dailyBillPoints(for: .weekOfYear, labelTemplate: "EEE")
        case .month:
            return dailyBillPoints(for: .month, labelTemplate: "d")
        case .year:
            return monthlyBillPoints()
        }
    }

    func billCategoryPoints(for period: StatisticsBillPeriod) -> [StatisticsBillCategoryPoint] {
        let groupedBills = Dictionary(grouping: bills(in: period), by: \.billCategory)
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

    func billCount(for period: StatisticsBillPeriod) -> Int {
        bills(in: period).count
    }

    func bills(for period: StatisticsBillPeriod) -> [BillRecord] {
        bills(in: period).sorted { $0.occurredAt > $1.occurredAt }
    }

    func billTotalCents(for period: StatisticsBillPeriod) -> Int64 {
        bills(in: period).reduce(0) { $0 + $1.finalAmountCents }
    }

    func billDiscountTotalCents(for period: StatisticsBillPeriod) -> Int64 {
        bills(in: period).reduce(0) { $0 + $1.discountAmountCents }
    }

    func billAverageCents(for period: StatisticsBillPeriod) -> Int64 {
        let periodBills = bills(in: period)
        guard periodBills.isEmpty == false else { return 0 }
        return periodBills.reduce(0) { $0 + $1.finalAmountCents } / Int64(periodBills.count)
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

    private func makeSummary(route: ToolRoute, dates: [Date]) -> StatisticsFeatureSummary {
        StatisticsFeatureSummary(
            route: route,
            count: dates.count,
            todayCount: dates.filter(calendar.isDateInToday).count,
            latestDate: dates.max()
        )
    }

    private func dailyBillPoints(for component: Calendar.Component, labelTemplate: String) -> [StatisticsBillChartPoint] {
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
                    label: format(day, template: labelTemplate),
                    totalCents: dayBills.reduce(0) { $0 + $1.finalAmountCents },
                    count: dayBills.count
                )
            )
            day = nextDay
        }

        return points
    }

    private func monthlyBillPoints() -> [StatisticsBillChartPoint] {
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
                    label: format(month, template: "MMM"),
                    totalCents: monthBills.reduce(0) { $0 + $1.finalAmountCents },
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

    private func date(milliseconds: Int64) -> Date {
        Date(timeIntervalSince1970: Double(milliseconds) / 1000)
    }

    private func format(_ date: Date, template: String) -> String {
        let formatter = DateFormatter()
        formatter.locale = AppLocalization.currentLocale
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
