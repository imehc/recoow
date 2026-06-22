import Foundation
import GRDB

/// 一条本地打卡。系统通知可被用户关闭，数据库记录仍保留为离线历史。
struct ReminderRecord: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "reminders"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var title: String
    var note: String?
    var memoryIcon: String
    var imageData: Data?
    var scheduledAt: Int64
    var leadTimeMinutes: Int
    var isEnabled: Bool
    var scheduleKindRawValue: String
    var endAt: Int64?
    var reminderTimeMinutes: Int
    var weekdaysRawValue: String?
    var continuousDays: Int
    var completedAt: Int64?
    var importedCompletedDays: Int
    var completedDateKeysRawValue: String?
    var completionRecordsRawValue: String?

    enum CodingKeys: String, CodingKey {
        case id
        case createdAt = "created_at"
        case updatedAt = "updated_at"
        case deletedAt = "deleted_at"
        case syncStatus = "sync_status"
        case deviceID = "device_id"
        case serverVersion = "server_version"
        case title
        case note
        case memoryIcon = "memory_icon"
        case imageData = "image_data"
        case scheduledAt = "scheduled_at"
        case leadTimeMinutes = "lead_time_minutes"
        case isEnabled = "is_enabled"
        case scheduleKindRawValue = "schedule_kind"
        case endAt = "end_at"
        case reminderTimeMinutes = "reminder_time_minutes"
        case weekdaysRawValue = "weekdays"
        case continuousDays = "continuous_days"
        case completedAt = "completed_at"
        case importedCompletedDays = "imported_completed_days"
        case completedDateKeysRawValue = "completed_date_keys"
        case completionRecordsRawValue = "completion_records"
    }

    var scheduledDate: Date {
        Date(timeIntervalSince1970: Double(scheduledAt) / 1000)
    }

    var endDate: Date? {
        endAt.map { Date(timeIntervalSince1970: Double($0) / 1000) }
    }

    var leadTime: ReminderLeadTime {
        ReminderLeadTime(rawValue: leadTimeMinutes) ?? .none
    }

    var scheduleKind: ReminderScheduleKind {
        ReminderScheduleKind(rawValue: scheduleKindRawValue) ?? .single
    }

    var selectedWeekdays: [Int] {
        weekdaysRawValue?
            .split(separator: ",")
            .compactMap { Int($0) }
            .filter { (1...7).contains($0) }
            .sorted() ?? []
    }

    var completionRecords: [ReminderCheckInCompletion] {
        var decodedRecords: [ReminderCheckInCompletion] = {
            guard let completionRecordsRawValue,
                  let data = completionRecordsRawValue.data(using: .utf8),
                  let records = try? JSONDecoder().decode([ReminderCheckInCompletion].self, from: data) else {
                return []
            }

            return records
        }()

        let decodedDateKeys = Set(decodedRecords.map(\.dateKey))
        let legacyRecords = legacyCompletedDateKeys
            .subtracting(decodedDateKeys)
            .map { dateKey in
                ReminderCheckInCompletion.make(
                    dateKey: dateKey,
                    completedAt: updatedAt,
                    kind: .checkIn,
                    note: nil
                )
            }

        decodedRecords.append(contentsOf: legacyRecords)
        return decodedRecords.sorted { $0.dateKey < $1.dateKey }
    }

    var completedDateKeys: Set<String> {
        Set(completionRecords.map(\.dateKey))
    }

    var historyRecords: [ReminderHistoryRecord] {
        completionRecords.map { completion in
            ReminderHistoryRecord(reminder: self, completion: completion)
        }
    }

    private var legacyCompletedDateKeys: Set<String> {
        Set(
            completedDateKeysRawValue?
                .split(separator: ",")
                .map(String.init) ?? []
        )
    }

    var isCompleted: Bool {
        if scheduleKind == .dailyGoal {
            return false
        }

        return completedAt != nil || isProgressFullyCompleted
    }

    var isUpcoming: Bool {
        let status = checkInStatus()
        return status == .ready || status == .upcoming
    }

    var nextOccurrenceDate: Date? {
        occurrenceDates(maxCount: 1).first
    }

    var todayOccurrenceDate: Date? {
        occurrenceDate(on: Date())
    }

    var needsCheckInToday: Bool {
        needsCheckIn(on: Date())
    }

    func needsCheckIn(on date: Date, calendar: Calendar = .current) -> Bool {
        canCheckIn(on: date, calendar: calendar)
    }

    var isTodayCompleted: Bool {
        isOccurrenceCompleted(on: Date())
    }

    var scheduleTitle: String {
        scheduleTitle(locale: AppLocalization.currentLocale)
    }

    func scheduleTitle(locale: Locale) -> String {
        switch scheduleKind {
        case .single:
            return AppFormatters.dateTime(milliseconds: scheduledAt, locale: locale)
        case .dateRange:
            guard let endAt else { return scheduleKind.title }
            return "\(AppFormatters.date(milliseconds: scheduledAt, locale: locale)) - \(AppFormatters.date(milliseconds: endAt, locale: locale))"
        case .weekdays:
            return AppLocalization.string("工作日")
        case .weekly:
            let titles = selectedWeekdays
                .map { weekday in Self.weekdayTitle(weekday) }
                .map(AppLocalization.string)
                .joined(separator: AppLocalization.string("列表分隔符"))
            return titles.isEmpty ? AppLocalization.string("每周几") : titles
        case .continuousDays:
            return AppLocalization.format("连续挑战 %d 天", max(1, continuousDays))
        case .dailyGoal:
            return AppLocalization.string("每天坚持")
        }
    }

    var progressText: String? {
        guard let progressTotalDays, progressTotalDays > 1 else {
            return nil
        }

        return AppLocalization.format("%d/%d 天", progressCompletedDays, progressTotalDays)
    }

    var progressFraction: Double? {
        guard let progressTotalDays, progressTotalDays > 0 else {
            return nil
        }

        return min(1, max(0, Double(progressCompletedDays) / Double(progressTotalDays)))
    }

    var progressTotalDays: Int? {
        switch scheduleKind {
        case .dateRange:
            guard let endDate else { return nil }
            return numberOfDays(from: scheduledDate, through: endDate)
        case .weekdays, .weekly, .continuousDays:
            return max(1, continuousDays)
        case .dailyGoal:
            return nil
        case .single:
            return 1
        }
    }

    var progressCompletedDays: Int {
        guard let progressTotalDays else { return 0 }
        return min(progressTotalDays, totalCheckInDays)
    }

    var progressRemainingDays: Int? {
        guard let progressTotalDays else { return nil }
        return max(0, progressTotalDays - progressCompletedDays)
    }

    var isProgressFullyCompleted: Bool {
        guard let progressTotalDays else { return false }
        return progressCompletedDays >= progressTotalDays
    }

    var totalCheckInDays: Int {
        return max(0, importedCompletedDays + completedDateKeys.count)
    }

    var goalSummaryText: String? {
        guard scheduleKind == .dailyGoal else { return nil }
        return AppLocalization.format("已坚持 %d 天，累计打卡 %d 天", elapsedGoalDays(), totalCheckInDays)
    }

    func checkInStatus(on date: Date = Date(), calendar: Calendar = .current) -> ReminderCheckInStatus {
        if isCompleted {
            return .completed
        }

        if isEnabled == false {
            return .disabled
        }

        if isOccurrenceCompleted(on: date, calendar: calendar) {
            return .checkedInToday
        }

        if canCheckIn(on: date, calendar: calendar) {
            return .ready
        }

        if isBroken(on: date, calendar: calendar) {
            return .broken
        }

        if occurrenceDates(maxCount: 1, from: date, calendar: calendar).isEmpty == false {
            return .upcoming
        }

        return .ended
    }

    func canCheckIn(on date: Date = Date(), calendar: Calendar = .current) -> Bool {
        guard isEnabled,
              isCompleted == false,
              occurrenceDate(on: date, calendar: calendar) != nil,
              isOccurrenceCompleted(on: date, calendar: calendar) == false else {
            return false
        }

        if scheduleKind == .continuousDays {
            return firstMissedCheckInDate(before: date, calendar: calendar) == nil
        }

        return true
    }

    func isBroken(on date: Date = Date(), calendar: Calendar = .current) -> Bool {
        firstMissedCheckInDate(before: date, calendar: calendar) != nil
    }

    func firstMissedCheckInDate(before date: Date = Date(), calendar: Calendar = .current) -> Date? {
        guard (scheduleKind == .continuousDays || scheduleKind == .dailyGoal), isCompleted == false else { return nil }

        let startDay = calendar.startOfDay(for: scheduledDate)
        let today = calendar.startOfDay(for: date)
        guard let yesterday = calendar.date(byAdding: .day, value: -1, to: today) else { return nil }

        let endDay = scheduleKind == .continuousDays ? continuousEndDay(calendar: calendar) : yesterday
        let lastDayToCheck = min(yesterday, endDay)
        guard lastDayToCheck >= startDay else { return nil }

        var currentDay = startDay
        while currentDay <= lastDayToCheck {
            if isOccurrenceCompleted(on: currentDay, calendar: calendar) == false {
                return currentDay
            }

            guard let nextDay = calendar.date(byAdding: .day, value: 1, to: currentDay) else {
                break
            }
            currentDay = nextDay
        }

        return nil
    }

    func currentStreakDays(on date: Date = Date(), calendar: Calendar = .current) -> Int {
        let startDay = calendar.startOfDay(for: scheduledDate)
        var currentDay = calendar.startOfDay(for: date)

        if isOccurrenceCompleted(on: currentDay, calendar: calendar) == false {
            guard let previousDay = calendar.date(byAdding: .day, value: -1, to: currentDay) else {
                return 0
            }
            currentDay = previousDay
        }

        if currentDay < startDay {
            return scheduleKind == .dailyGoal ? max(0, importedCompletedDays) : 0
        }

        var count = 0
        while currentDay >= startDay {
            guard isOccurrenceCompleted(on: currentDay, calendar: calendar) else {
                break
            }

            count += 1
            guard let previousDay = calendar.date(byAdding: .day, value: -1, to: currentDay) else {
                break
            }
            currentDay = previousDay
        }

        if scheduleKind == .dailyGoal, currentDay < startDay {
            return max(0, importedCompletedDays) + count
        }

        return count
    }

    func longestStreakDays(calendar: Calendar = .current) -> Int {
        let days = completedDateKeys
            .compactMap { Self.date(fromDateKey: $0, calendar: calendar) }
            .sorted()

        guard days.isEmpty == false else { return max(0, importedCompletedDays) }

        var longest = 1
        var current = 1

        for index in days.indices.dropFirst() {
            let previousDay = days[days.index(before: index)]
            let day = days[index]
            let distance = calendar.dateComponents([.day], from: previousDay, to: day).day ?? 0

            if distance == 1 {
                current += 1
            } else if distance > 1 {
                longest = max(longest, current)
                current = 1
            }
        }

        longest = max(longest, current)

        if scheduleKind == .dailyGoal {
            longest = max(longest, importedCompletedDays + completedDaysFromStart(calendar: calendar))
        }

        return longest
    }

    func elapsedGoalDays(on date: Date = Date(), calendar: Calendar = .current) -> Int {
        numberOfDays(from: scheduledDate, through: max(date, scheduledDate), calendar: calendar)
    }

    func occurrenceDates(
        maxCount: Int = 32,
        from referenceDate: Date = Date(),
        calendar: Calendar = .current
    ) -> [Date] {
        guard maxCount > 0, isCompleted == false else { return [] }

        switch scheduleKind {
        case .single:
            return scheduledDate > referenceDate ? [scheduledDate] : []
        case .dateRange:
            return recurringDates(
                allowedWeekdays: nil,
                endDate: endDate,
                maxCount: maxCount,
                from: referenceDate,
                calendar: calendar
            )
        case .weekdays:
            return recurringDates(
                allowedWeekdays: Set(2...6),
                endDate: endDate,
                maxCount: maxCount,
                from: referenceDate,
                calendar: calendar
            )
        case .weekly:
            return recurringDates(
                allowedWeekdays: Set(selectedWeekdays),
                endDate: endDate,
                maxCount: maxCount,
                from: referenceDate,
                calendar: calendar
            )
        case .continuousDays:
            let endDate = calendar.date(
                byAdding: .day,
                value: max(1, continuousDays) - 1,
                to: calendar.startOfDay(for: scheduledDate)
            )
            return recurringDates(
                allowedWeekdays: nil,
                endDate: endDate,
                maxCount: maxCount,
                from: referenceDate,
                calendar: calendar,
                requiresContinuousChain: true
            )
        case .dailyGoal:
            return recurringDates(
                allowedWeekdays: nil,
                endDate: nil,
                maxCount: maxCount,
                from: referenceDate,
                calendar: calendar
            )
        }
    }

    static func makeNew(
        title: String,
        note: String?,
        memoryIcon: String,
        imageData: Data?,
        scheduledAt: Int64,
        leadTimeMinutes: Int,
        deviceID: String
    ) -> ReminderRecord {
        let now = SyncableTimestamp.nowMilliseconds()
        return ReminderRecord(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            title: title,
            note: note,
            memoryIcon: memoryIcon,
            imageData: imageData,
            scheduledAt: scheduledAt,
            leadTimeMinutes: leadTimeMinutes,
            isEnabled: true,
            scheduleKindRawValue: ReminderScheduleKind.dailyGoal.rawValue,
            endAt: nil,
            reminderTimeMinutes: minutesSinceStartOfDay(for: Date(timeIntervalSince1970: Double(scheduledAt) / 1000)),
            weekdaysRawValue: nil,
            continuousDays: 30,
            completedAt: nil,
            importedCompletedDays: 0,
            completedDateKeysRawValue: nil,
            completionRecordsRawValue: nil
        )
    }

    mutating func markOccurrenceCompleted(
        on date: Date = Date(),
        calendar: Calendar = .current,
        kind: ReminderCheckInCompletionKind = .checkIn,
        note: String? = nil
    ) {
        guard let occurrenceDate = occurrenceDate(on: date, calendar: calendar) else { return }

        let dateKey = Self.dateKey(for: occurrenceDate, calendar: calendar)
        let normalizedNote = note?.trimmingCharacters(in: .whitespacesAndNewlines)
        var records = completionRecords.filter { $0.dateKey != dateKey }
        records.append(
            ReminderCheckInCompletion.make(
                dateKey: dateKey,
                kind: kind,
                note: normalizedNote?.isEmpty == true ? nil : normalizedNote
            )
        )
        completionRecordsRawValue = Self.rawValue(for: records)
        completedDateKeysRawValue = Self.rawValue(for: Set(records.map(\.dateKey)))

        if scheduleKind == .dailyGoal {
            completedAt = nil
        } else if scheduleKind == .single || isProgressFullyCompleted {
            completedAt = SyncableTimestamp.nowMilliseconds()
        }
    }

    mutating func clearCompletion() {
        completedAt = nil
        completedDateKeysRawValue = nil
        completionRecordsRawValue = nil
    }

    mutating func clearOccurrenceCompletion(on date: Date = Date(), calendar: Calendar = .current) -> Bool {
        guard let occurrenceDate = occurrenceDate(on: date, calendar: calendar) else { return false }

        let dateKey = Self.dateKey(for: occurrenceDate, calendar: calendar)
        return clearOccurrenceCompletion(dateKey: dateKey)
    }

    mutating func clearOccurrenceCompletion(dateKey: String) -> Bool {
        let records = completionRecords
        let filteredRecords = records.filter { $0.dateKey != dateKey }
        guard filteredRecords.count != records.count else { return false }

        completionRecordsRawValue = Self.rawValue(for: filteredRecords)
        completedDateKeysRawValue = Self.rawValue(for: Set(filteredRecords.map(\.dateKey)))
        completedAt = nil
        return true
    }

    nonisolated static func weekdayTitle(_ weekday: Int) -> String {
        switch weekday {
        case 1:
            "周日"
        case 2:
            "周一"
        case 3:
            "周二"
        case 4:
            "周三"
        case 5:
            "周四"
        case 6:
            "周五"
        case 7:
            "周六"
        default:
            ""
        }
    }

    nonisolated static func minutesSinceStartOfDay(for date: Date, calendar: Calendar = .current) -> Int {
        let components = calendar.dateComponents([.hour, .minute], from: date)
        return (components.hour ?? 0) * 60 + (components.minute ?? 0)
    }

    nonisolated static func dateKey(for date: Date, calendar: Calendar = .current) -> String {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        let year = components.year ?? 0
        let month = components.month ?? 0
        let day = components.day ?? 0
        return String(format: "%04d-%02d-%02d", year, month, day)
    }

    nonisolated static func date(fromDateKey dateKey: String, calendar: Calendar = .current) -> Date? {
        let components = dateKey.split(separator: "-").compactMap { Int($0) }
        guard components.count == 3 else { return nil }

        return calendar.date(
            from: DateComponents(
                calendar: calendar,
                year: components[0],
                month: components[1],
                day: components[2]
            )
        )
    }

    private nonisolated static func rawValue(for dateKeys: Set<String>) -> String? {
        let value = dateKeys.sorted().joined(separator: ",")
        return value.isEmpty ? nil : value
    }

    private nonisolated static func rawValue(for completionRecords: [ReminderCheckInCompletion]) -> String? {
        let sortedRecords = completionRecords.sorted { $0.dateKey < $1.dateKey }
        guard sortedRecords.isEmpty == false,
              let data = try? JSONEncoder().encode(sortedRecords) else {
            return nil
        }

        return String(data: data, encoding: .utf8)
    }

    func occurrenceDate(on date: Date, calendar: Calendar = .current) -> Date? {
        let targetDay = calendar.startOfDay(for: date)
        let startDay = calendar.startOfDay(for: scheduledDate)

        guard targetDay >= startDay else { return nil }

        let effectiveEndDay: Date?
        switch scheduleKind {
        case .single:
            effectiveEndDay = startDay
        case .dateRange:
            effectiveEndDay = endDate.map { calendar.startOfDay(for: $0) }
        case .weekdays, .weekly:
            effectiveEndDay = endDate.map { calendar.startOfDay(for: $0) }
        case .continuousDays:
            effectiveEndDay = continuousEndDay(calendar: calendar)
        case .dailyGoal:
            effectiveEndDay = nil
        }

        if let effectiveEndDay, targetDay > effectiveEndDay {
            return nil
        }

        let weekday = calendar.component(.weekday, from: targetDay)
        switch scheduleKind {
        case .weekdays:
            guard (2...6).contains(weekday) else { return nil }
        case .weekly:
            guard selectedWeekdays.contains(weekday) else { return nil }
        case .single, .dateRange, .continuousDays, .dailyGoal:
            break
        }

        return dateByApplyingReminderTime(to: targetDay, calendar: calendar)
    }

    private func recurringDates(
        allowedWeekdays: Set<Int>?,
        endDate: Date?,
        maxCount: Int,
        from referenceDate: Date,
        calendar: Calendar,
        requiresContinuousChain: Bool = false
    ) -> [Date] {
        var dates: [Date] = []
        let startDay = calendar.startOfDay(for: scheduledDate)
        let referenceDay = calendar.startOfDay(for: referenceDate)
        var currentDay = max(startDay, referenceDay)
        let effectiveEndDay = endDate.map { calendar.startOfDay(for: $0) }
        let searchLimit = calendar.date(byAdding: .day, value: 370, to: currentDay) ?? currentDay

        while dates.count < maxCount && currentDay <= searchLimit {
            if let effectiveEndDay, currentDay > effectiveEndDay {
                break
            }

            let weekday = calendar.component(.weekday, from: currentDay)
            let weekdayMatches = allowedWeekdays?.contains(weekday) ?? true

            if weekdayMatches,
               (requiresContinuousChain == false || isContinuityReady(for: currentDay, calendar: calendar)),
               let occurrence = dateByApplyingReminderTime(to: currentDay, calendar: calendar),
               isOccurrenceCompleted(on: occurrence, calendar: calendar) == false,
               occurrence > referenceDate {
                dates.append(occurrence)
            }

            guard let nextDay = calendar.date(byAdding: .day, value: 1, to: currentDay) else {
                break
            }
            currentDay = nextDay
        }

        return dates
    }

    private func dateByApplyingReminderTime(to day: Date, calendar: Calendar) -> Date? {
        let minutes: Int

        if reminderTimeMinutes > 0 {
            minutes = reminderTimeMinutes
        } else {
            minutes = Self.minutesSinceStartOfDay(for: scheduledDate, calendar: calendar)
        }

        return calendar.date(
            bySettingHour: minutes / 60,
            minute: minutes % 60,
            second: 0,
            of: day
        )
    }

    private func numberOfDays(from startDate: Date, through endDate: Date, calendar: Calendar = .current) -> Int {
        let startDay = calendar.startOfDay(for: startDate)
        let endDay = calendar.startOfDay(for: endDate)
        let days = calendar.dateComponents([.day], from: startDay, to: endDay).day ?? 0
        return max(1, days + 1)
    }

    private func isOccurrenceCompleted(on date: Date, calendar: Calendar = .current) -> Bool {
        completedDateKeys.contains(Self.dateKey(for: date, calendar: calendar))
    }

    private func completedDaysFromStart(calendar: Calendar) -> Int {
        let startDay = calendar.startOfDay(for: scheduledDate)
        var currentDay = startDay
        var count = 0

        while isOccurrenceCompleted(on: currentDay, calendar: calendar) {
            count += 1
            guard let nextDay = calendar.date(byAdding: .day, value: 1, to: currentDay) else {
                break
            }
            currentDay = nextDay
        }

        return count
    }

    private func continuousEndDay(calendar: Calendar) -> Date {
        calendar.date(
            byAdding: .day,
            value: max(1, continuousDays) - 1,
            to: calendar.startOfDay(for: scheduledDate)
        ) ?? calendar.startOfDay(for: scheduledDate)
    }

    private func isContinuityReady(for targetDay: Date, calendar: Calendar) -> Bool {
        guard scheduleKind == .continuousDays else { return true }

        let startDay = calendar.startOfDay(for: scheduledDate)
        let targetDay = calendar.startOfDay(for: targetDay)
        guard targetDay > startDay else { return true }
        guard let previousDay = calendar.date(byAdding: .day, value: -1, to: targetDay) else { return true }

        var currentDay = startDay
        while currentDay <= previousDay {
            if isOccurrenceCompleted(on: currentDay, calendar: calendar) == false {
                return false
            }

            guard let nextDay = calendar.date(byAdding: .day, value: 1, to: currentDay) else {
                break
            }
            currentDay = nextDay
        }

        return true
    }
}
