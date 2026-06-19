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

    var completedDateKeys: Set<String> {
        Set(
            completedDateKeysRawValue?
                .split(separator: ",")
                .map(String.init) ?? []
        )
    }

    var isCompleted: Bool {
        completedAt != nil || isProgressFullyCompleted
    }

    var isUpcoming: Bool {
        isEnabled && isCompleted == false && deletedAt == nil && nextOccurrenceDate != nil
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
        guard isCompleted == false,
              let occurrenceDate = occurrenceDate(on: date, calendar: calendar),
              occurrenceDate > date else {
            return false
        }

        return isOccurrenceCompleted(on: occurrenceDate, calendar: calendar) == false
    }

    var isTodayCompleted: Bool {
        guard let todayOccurrenceDate else { return false }
        return isOccurrenceCompleted(on: todayOccurrenceDate)
    }

    var scheduleTitle: String {
        switch scheduleKind {
        case .single:
            return AppFormatters.dateTime(milliseconds: scheduledAt)
        case .dateRange:
            guard let endAt else { return scheduleKind.title }
            return "\(AppFormatters.date(milliseconds: scheduledAt)) - \(AppFormatters.date(milliseconds: endAt))"
        case .weekdays:
            return "工作日"
        case .weekly:
            let titles = selectedWeekdays.map(Self.weekdayTitle).joined(separator: "、")
            return titles.isEmpty ? "每周几" : titles
        case .continuousDays:
            return "连续 \(max(1, continuousDays)) 天"
        }
    }

    var progressText: String? {
        guard let progressTotalDays, progressTotalDays > 1 else {
            return nil
        }

        return "\(progressCompletedDays)/\(progressTotalDays) 天"
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
        case .continuousDays:
            return max(1, continuousDays)
        case .single, .weekdays, .weekly:
            return nil
        }
    }

    var progressCompletedDays: Int {
        guard let progressTotalDays else { return 0 }
        return min(progressTotalDays, max(0, importedCompletedDays + completedDateKeys.count))
    }

    var isProgressFullyCompleted: Bool {
        guard let progressTotalDays else { return false }
        return progressCompletedDays >= progressTotalDays
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
            scheduleKindRawValue: ReminderScheduleKind.single.rawValue,
            endAt: nil,
            reminderTimeMinutes: minutesSinceStartOfDay(for: Date(timeIntervalSince1970: Double(scheduledAt) / 1000)),
            weekdaysRawValue: nil,
            continuousDays: 30,
            completedAt: nil,
            importedCompletedDays: 0,
            completedDateKeysRawValue: nil
        )
    }

    mutating func markOccurrenceCompleted(on date: Date = Date(), calendar: Calendar = .current) {
        guard let occurrenceDate = occurrenceDate(on: date, calendar: calendar) else { return }

        var keys = completedDateKeys
        keys.insert(Self.dateKey(for: occurrenceDate, calendar: calendar))
        completedDateKeysRawValue = Self.rawValue(for: keys)

        if scheduleKind == .single || isProgressFullyCompleted {
            completedAt = SyncableTimestamp.nowMilliseconds()
        }
    }

    mutating func clearCompletion() {
        completedAt = nil
        completedDateKeysRawValue = nil
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

    private nonisolated static func rawValue(for dateKeys: Set<String>) -> String? {
        let value = dateKeys.sorted().joined(separator: ",")
        return value.isEmpty ? nil : value
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
            effectiveEndDay = calendar.date(
                byAdding: .day,
                value: max(1, continuousDays) - 1,
                to: startDay
            )
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
        case .single, .dateRange, .continuousDays:
            break
        }

        return dateByApplyingReminderTime(to: targetDay, calendar: calendar)
    }

    private func recurringDates(
        allowedWeekdays: Set<Int>?,
        endDate: Date?,
        maxCount: Int,
        from referenceDate: Date,
        calendar: Calendar
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
}
