import Foundation
import GRDB

/// 一条纪念日。日期按原始发生日保存，重复提醒按下一次发生日期动态计算。
struct AnniversaryRecord: Identifiable, Codable, Hashable, Sendable, FetchableRecord, PersistableRecord, SyncableRecord, ConflictComparableRecord {
    static let databaseTableName = "anniversaries"

    var id: String
    var createdAt: Int64
    var updatedAt: Int64
    var deletedAt: Int64?
    var syncStatus: SyncStatus
    var deviceID: String
    var serverVersion: Int64?

    var title: String
    var note: String?
    var categoryRawValue: String
    var occurredAt: Int64
    var isYearly: Bool
    var leadTimeMinutes: Int
    var isEnabled: Bool
    var reminderTimeMinutes: Int

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
        case categoryRawValue = "category"
        case occurredAt = "occurred_at"
        case isYearly = "is_yearly"
        case leadTimeMinutes = "lead_time_minutes"
        case isEnabled = "is_enabled"
        case reminderTimeMinutes = "reminder_time_minutes"
    }

    var occurredDate: Date {
        Date(timeIntervalSince1970: Double(occurredAt) / 1000)
    }

    var category: AnniversaryCategory {
        AnniversaryCategory(rawValue: categoryRawValue) ?? .other
    }

    var leadTime: ReminderLeadTime {
        ReminderLeadTime(rawValue: leadTimeMinutes) ?? .none
    }

    var nextOccurrenceDate: Date? {
        nextOccurrenceDate(from: Date())
    }

    var isExpired: Bool {
        isYearly == false && nextOccurrenceDate == nil
    }

    var daysSince: Int {
        days(from: occurredDate, to: Date())
    }

    var yearsSince: Int {
        max(0, Calendar.current.dateComponents([.year], from: occurredDate, to: Date()).year ?? 0)
    }

    func nextOccurrenceDate(from referenceDate: Date, calendar: Calendar = .current) -> Date? {
        let baseOccurrenceDate = dateByApplyingReminderTime(to: occurredDate, calendar: calendar)

        guard isYearly else {
            return baseOccurrenceDate >= referenceDate ? baseOccurrenceDate : nil
        }

        if referenceDate < baseOccurrenceDate {
            return baseOccurrenceDate
        }

        let referenceYear = calendar.component(.year, from: referenceDate)
        let currentYearOccurrence = occurrenceDate(in: referenceYear, calendar: calendar)

        if currentYearOccurrence >= referenceDate {
            return currentYearOccurrence
        }

        return occurrenceDate(in: referenceYear + 1, calendar: calendar)
    }

    func occurrenceDate(on date: Date, calendar: Calendar = .current) -> Date? {
        let targetDay = calendar.startOfDay(for: date)
        guard targetDay >= calendar.startOfDay(for: occurredDate) else { return nil }

        if isYearly {
            let targetYear = calendar.component(.year, from: targetDay)
            let occurrence = occurrenceDate(in: targetYear, calendar: calendar)
            return calendar.isDate(occurrence, inSameDayAs: targetDay) ? occurrence : nil
        }

        let occurrence = dateByApplyingReminderTime(to: occurredDate, calendar: calendar)
        return calendar.isDate(occurrence, inSameDayAs: targetDay) ? occurrence : nil
    }

    func daysUntilNext(from referenceDate: Date = Date(), calendar: Calendar = .current) -> Int? {
        guard let nextOccurrence = nextOccurrenceDate(from: referenceDate, calendar: calendar) else { return nil }
        return days(from: referenceDate, to: nextOccurrence, calendar: calendar)
    }

    func occurrenceYears(on date: Date = Date(), calendar: Calendar = .current) -> Int {
        let targetYear = calendar.component(.year, from: date)
        let startYear = calendar.component(.year, from: occurredDate)
        return max(0, targetYear - startYear)
    }

    static func makeNew(
        title: String,
        note: String?,
        category: AnniversaryCategory,
        occurredAt: Int64,
        isYearly: Bool,
        leadTime: ReminderLeadTime,
        isEnabled: Bool,
        reminderTimeMinutes: Int,
        deviceID: String
    ) -> AnniversaryRecord {
        let now = SyncableTimestamp.nowMilliseconds()
        return AnniversaryRecord(
            id: UUID().uuidString,
            createdAt: now,
            updatedAt: now,
            deletedAt: nil,
            syncStatus: .pending,
            deviceID: deviceID,
            serverVersion: nil,
            title: title,
            note: note,
            categoryRawValue: category.rawValue,
            occurredAt: occurredAt,
            isYearly: isYearly,
            leadTimeMinutes: leadTime.rawValue,
            isEnabled: isEnabled,
            reminderTimeMinutes: reminderTimeMinutes
        )
    }

    nonisolated static func minutesSinceStartOfDay(for date: Date, calendar: Calendar = .current) -> Int {
        let components = calendar.dateComponents([.hour, .minute], from: date)
        return (components.hour ?? 0) * 60 + (components.minute ?? 0)
    }

    private func occurrenceDate(in year: Int, calendar: Calendar) -> Date {
        let components = calendar.dateComponents([.month, .day], from: occurredDate)
        let month = components.month ?? 1
        let day = components.day ?? 1
        let hour = reminderTimeMinutes / 60
        let minute = reminderTimeMinutes % 60

        if let date = calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute)) {
            return date
        }

        return calendar.date(from: DateComponents(year: year, month: month, day: 28, hour: hour, minute: minute))
            ?? dateByApplyingReminderTime(to: occurredDate, calendar: calendar)
    }

    private func dateByApplyingReminderTime(to date: Date, calendar: Calendar) -> Date {
        calendar.date(
            bySettingHour: reminderTimeMinutes / 60,
            minute: reminderTimeMinutes % 60,
            second: 0,
            of: date
        ) ?? date
    }

    private func days(from startDate: Date, to endDate: Date, calendar: Calendar = .current) -> Int {
        let startDay = calendar.startOfDay(for: startDate)
        let endDay = calendar.startOfDay(for: endDate)
        return calendar.dateComponents([.day], from: startDay, to: endDay).day ?? 0
    }
}
